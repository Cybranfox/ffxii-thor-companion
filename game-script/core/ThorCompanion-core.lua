--ThorCompanion-core.lua: game-side script for the FFXII Thor Companion (https://github.com/Cybranfox/ffxii-thor-companion).
--From 0.11.0 it is not installed in the game folder itself: the small loader there (ThorCompanion.lua, game-script/loader)
--checks this file's signature at every game start and only then runs it, as a text chunk in the normal Lua environment.
--Runs under ffgriever's FF12 LUA Script Loader. Reads the running game's state (location, map, party HP/MP, gambits,
--licences, gear, stock) and writes it as JSON to OUT_DIR for the companion app on the second screen.
--Read-only, except the opt-in gambit editor: it stays OFF unless <BASE_DIR>cmd/ARMED existed when the game started,
--and then only writes the one gambit slot a companion command names, after re-checking it. Never calls game functions,
--never touches saves, never opens a network connection. Only the supported game build is read (see supportedBuild).
--Protocol: docs/PROTOCOL.md. History: CHANGELOG.md.

local PROTOCOL = 1
local PRODUCER = { name = "ThorCompanion-lua", version = "0.16.1-beta" }

--Under GameNative on the AYN Thor, Wine drive D: is /sdcard/Download, so this is Download/FFXII_companion on the device.
local BASE_DIR = "D:/FFXII_companion/"
local OUT_DIR = BASE_DIR .. "live/"
local SLOT_FILES = { OUT_DIR .. "state_a.json", OUT_DIR .. "state_b.json" }
local POLL_MS = 500         --session/map poll; zone changes also publish immediately via onMapJump
local HEARTBEAT_MS = 2000   --republish even when nothing changed, so the reader can detect staleness

--Paths are relative to the game's x64 directory, the same convention the Insurgent scripts use.
local MOD_FILES = {
  { id = "ffgriever.efl",          kind = "module", path = "modules/ff12-file-loader.dll" },
  { id = "ffgriever.lualoader",    kind = "module", path = "modules/ff12-lua-loader.dll" },
  { id = "insurgents.manifesto",   kind = "lua",    path = "scripts/TheInsurgentsManifesto.lua" },
  { id = "insurgents.descinv",     kind = "lua",    path = "scripts/TheInsurgentsDescriptiveInventory.lua" },
  { id = "fehdead.dynequipdesc",   kind = "lua",    path = "scripts/DynamicEquipmentDescription.lua" },
  { id = "lpc.foedexprimer",       kind = "lua",    path = "scripts/FoedexClanPrimerTextReplacer.lua" },
}

local seq = 0
local slot = 1
local lastDigest = nil
local lastPublishClock = 0
local mapJumps = 0
local lastJump = nil

--Minimal JSON encoder (sorted keys, so identical state gives identical text).
local function encode(v)
  local t = type(v)
  if t == "nil" then return "null" end
  if t == "boolean" then return v and "true" or "false" end
  if t == "number" then
    if v ~= v or v == math.huge or v == -math.huge then return "null" end
    if math.type and math.type(v) == "integer" then return string.format("%d", v) end
    return string.format("%.6g", v)
  end
  if t == "string" then
    return '"' .. v:gsub('[%c"\\]', function(c)
      local map = { ['"'] = '\\"', ['\\'] = '\\\\', ['\n'] = '\\n', ['\r'] = '\\r', ['\t'] = '\\t' }
      return map[c] or string.format("\\u%04x", c:byte())
    end) .. '"'
  end
  if t == "table" then
    if #v > 0 then
      local parts = {}
      for i = 1, #v do parts[i] = encode(v[i]) end
      return "[" .. table.concat(parts, ",") .. "]"
    end
    local keys = {}
    for k in pairs(v) do keys[#keys + 1] = tostring(k) end
    table.sort(keys)
    local parts = {}
    for _, k in ipairs(keys) do parts[#parts + 1] = encode(k) .. ":" .. encode(v[k]) end
    return "{" .. table.concat(parts, ",") .. "}"
  end
  return "null"
end

local function safeCall(fn)
  if not fn then return nil end
  local ok, v = pcall(fn)
  if ok then return v end
  return nil
end

local function fileSize(path)
  if lfs and lfs.attributes then
    local ok, size = pcall(lfs.attributes, path, "size")
    if ok then return size end
  end
  local f = io.open(path, "rb")
  if not f then return nil end
  local size = f:seek("end")
  f:close()
  return size
end

local function ensureOutDir()
  --The install batch creates OUT_DIR; these calls only cover a missing folder.
  if lfs and lfs.mkdir then
    pcall(lfs.mkdir, BASE_DIR:sub(1, -2))
    pcall(lfs.mkdir, OUT_DIR)
  elseif filesystem and filesystem.mkdirs then
    pcall(filesystem.mkdirs, OUT_DIR)
  end
end

--1.7.1 does not return a table (smoke test 2026-10-21), so accept a table, several values, or one
--string/number.
local function loaderVersion()
  local r = table.pack(pcall(getLibVersion))
  if not r[1] then return nil end
  local v = r[2]
  if type(v) == "table" then
    return string.format("%s.%s.%s", v.major or v[1] or "?", v.minor or v[2] or "?", v.patch or v[3] or "?")
  end
  --1.7.1 returns a sol usertype FF12LuaBind::tVersion with major/minor/patch fields (smoke test v0.3.0).
  if type(v) == "userdata" then
    local ok, s = pcall(function() return string.format("%s.%s.%s", v.major, v.minor, v.patch) end)
    if ok then return s end
  end
  if r.n > 2 then
    local parts = {}
    for i = 2, r.n do parts[#parts + 1] = tostring(r[i]) end
    return table.concat(parts, ".")
  end
  if v ~= nil then return tostring(v) end
  return nil
end

--Known narrow patches of the supported Steam build. A whole-file hash would reject these, so each
--site is classified separately: "vanilla", "applied" or "unknown".
--gn.595520.displaymodes: GameNative 1.3.0 gamefixes/STEAM_595520.kt, file offset 0xBCEF4 = RVA 0xBD8F4
--(.text raw 0x600 -> va 0x1000), "8B 41 20 39 41 34" followed by 74 31 (vanilla) or 90 90 (patched).
--Addresses passed to memory.* are absolute VAs (EFL_Module_Contract_2026-10-20.md section 4: the
--installed mods' original-byte tables match the exe only as VA). FFXII_TZA.exe has no DYNAMIC_BASE,
--so its image base is fixed at 0x120000, which the reader still confirms through the MZ/PE check.
local SUPPORTED_EXE_LENGTH = 32864752
local IMAGE_BASE = 0x120000
local PATCH_SITES = {
  { id = "gn.595520.displaymodes", rva = 0xBD8F4,
    prefix = { 0x8B, 0x41, 0x20, 0x39, 0x41, 0x34 },
    variants = { vanilla = { 0x74, 0x31 }, applied = { 0x90, 0x90 } } },
}

local function bytesMatch(base, expected)
  for i, b in ipairs(expected) do
    if memory.u8[base + i - 1] ~= b then return false end
  end
  return true
end

local function patchVariants()
  local result = {}
  for _, site in ipairs(PATCH_SITES) do
    local ok, state = pcall(function()
      local va = IMAGE_BASE + site.rva
      if not bytesMatch(va, site.prefix) then return "unknown" end
      for name, tail in pairs(site.variants) do
        if bytesMatch(va + #site.prefix, tail) then return name end
      end
      return "unknown"
    end)
    result[site.id] = ok and state or "unreadable"
  end
  return result
end

--Build identification. Reads the in-memory PE header at the fixed image base (VA 0x120000). If the
--"MZ"/"PE" signatures do not match there, the image was not loaded at its preferred base (possible
--only if a loader ignored the missing DYNAMIC_BASE flag), every VA constant in the installed mods is
--then wrong, and the reader must treat the build as unknown.
--The on-disk exe length is unchanged by the GameNative patch, so it stays a valid coarse check.
local function buildInfo()
  local size = fileSize("FFXII_TZA.exe")
  local info = {
    exeFileSize = size,
    exeLengthSupported = size == SUPPORTED_EXE_LENGTH,
    pe = nil,
    --Statically established (VA, fixed base); still to be confirmed once in-game by the MZ/PE check
    --below succeeding, after which the patch states are this session's observations.
    addrConvention = "va-static",
    imageBase = IMAGE_BASE,
    patches = patchVariants(),
    nativeUcrtbase = fileSize("ucrtbase.dll") ~= nil,
    verified = false,
  }
  local ok, pe = pcall(function()
    if memory.u16[IMAGE_BASE] ~= 0x5A4D then return nil end
    local lfanew = memory.u32[IMAGE_BASE + 0x3C]
    if memory.u32[IMAGE_BASE + lfanew] ~= 0x00004550 then return nil end
    local opt = IMAGE_BASE + lfanew + 0x18
    return {
      timeDateStamp = memory.u32[IMAGE_BASE + lfanew + 0x08],
      sizeOfImage = memory.u32[opt + 0x38],
      entryPoint = memory.u32[opt + 0x10],
      imageBaseField = memory.u64[opt + 0x18],
    }
  end)
  if ok and pe then info.pe = pe end
  --"verified" is set by the companion against its allow-list, never here.
  return info
end

--Menu-map reveal record (contract ffxii.reveal.v1). Established statically and confirmed on the Thor
--2026-10-21: root pointer [0x20927C0] = save base 0x2164280 + 0x8200; record = 32 bytes at root + 0x224 +
--slot*32; slot = low 12 bits of navimapdata.bin's 3-byte record indexed by map ID (0xFFF = none, max 0x180).
--Only the exact supported exe (PE TimeDateStamp) is read; any other build reports a status and no bytes.
local REVEAL_CONTRACT = "ffxii.reveal.v1"
local SUPPORTED_TIME_DATE_STAMP = 1573552087
local NAV_PTR = 0x2ADD100
local REVEAL_ROOT_PTR = 0x20927C0
local REVEAL_ROOT = 0x2164280 + 0x8200
local REVEAL_RECORD_OFFSET = 0x224
local REVEAL_RECORD_BYTES = 32
local REVEAL_MAX_SLOT = 0x180

local supportedBuild = (function()
local verified = false  --cache only a successful immutable image-header check
return function()
  if verified then return true end
  local ok, res = pcall(function()
    if memory.u16[IMAGE_BASE] ~= 0x5A4D then return false end
    local lfanew = memory.u32[IMAGE_BASE + 0x3C]
    if memory.u32[IMAGE_BASE + lfanew] ~= 0x00004550 then return false end
    return memory.u32[IMAGE_BASE + lfanew + 0x08] == SUPPORTED_TIME_DATE_STAMP
  end)
  verified = ok and res == true
  return verified
end

end)()

local function revealSlot(mapId)
  local ok, slot = pcall(function()
    local nav = memory.u64[NAV_PTR]
    if nav == nil or nav == 0 then return "nav_not_loaded" end
    if memory.u8[nav] ~= 0x4E or memory.u8[nav + 1] ~= 0x41 or memory.u8[nav + 2] ~= 0x56 then return "nav_not_loaded" end
    local count = memory.u32[nav + 4]
    if count < 1 or count > 4096 then return "nav_not_loaded" end
    if mapId < 0 or mapId >= count then return "no_slot" end
    local v = (memory.u8[nav + 9 + 3 * mapId] * 256 + memory.u8[nav + 8 + 3 * mapId]) & 0xFFF
    if v == 0xFFF or v > REVEAL_MAX_SLOT then return "no_slot" end
    return v
  end)
  if not ok then return "read_failed" end
  return slot
end

local function revealRecordHex(slot)
  local t = {}
  local base = REVEAL_ROOT + REVEAL_RECORD_OFFSET + slot * REVEAL_RECORD_BYTES
  for i = 0, REVEAL_RECORD_BYTES - 1 do t[#t + 1] = string.format("%02x", memory.u8[base + i]) end
  return table.concat(t)
end

local REVEAL_TABLE_MS = 10000
local revealTable, revealTableClock = nil, nil
--Slow readers use separate 500-ms poll phases; elapsed caps recover from skipped/delayed phases.
local forceTable = { force = true, poll = 0 }
function forceTable.due(last, now, modulus, phase, maxAge)
  return not last or (now ~= last and (forceTable.poll % modulus == phase or now - last >= maxAge))
end

--All slots 0..0x180 as "slot:hex,..." for non-zero records; nil when a read fails.
local function readRevealTable()
  local ok, res = pcall(function()
    local t = {}
    for slot = 0, REVEAL_MAX_SLOT do
      local a = REVEAL_ROOT + REVEAL_RECORD_OFFSET + slot * REVEAL_RECORD_BYTES
      local w, any = {}, false
      for i = 0, REVEAL_RECORD_BYTES - 4, 4 do
        local v = memory.u32[a + i]
        if v ~= 0 then any = true end
        w[#w + 1] = string.format("%02x%02x%02x%02x", v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF)
      end
      if any then t[#t + 1] = string.format("%d:", slot) .. table.concat(w) end
    end
    return table.concat(t, ",")
  end)
  return ok and res or nil
end

--Same gates as revealState (supported build, root pointer). Kept until the next refresh; "stable" as there.
local function revealTableState(force)
  local now = safeCall(event.getClock) or 0
  if not force and revealTable and not forceTable.due(revealTableClock, now, 20, 7, REVEAL_TABLE_MS * 1000) then
    return revealTable
  end
  revealTableClock = now
  local r = { contract = REVEAL_CONTRACT }
  if not supportedBuild() then r.status = "unsupported_build"
  else
    local okRoot, root = pcall(function() return memory.u64[REVEAL_ROOT_PTR] end)
    if not okRoot then r.status = "read_failed"
    elseif root ~= REVEAL_ROOT then r.status = "root_unexpected"
    else
      local a, b = readRevealTable(), readRevealTable()
      if not a or not b then r.status = "read_failed"
      elseif a ~= b then r.status = "unstable"
      else r.status = "ok"; r.records = a; r.stable = true; r.observedClockUs = now end
    end
  end
  revealTable = r
  return r
end

--Status values: ok, unsupported_build, no_map, nav_not_loaded, no_slot, root_unexpected, unstable, read_failed.
--"record" is present only with status ok. "stable" means two consecutive reads matched; it detects a write
--during the read, not a coherent snapshot of other game state.
local revealState = (function()
local function savedState(mapId)
  local r = { contract = REVEAL_CONTRACT, mapId = mapId }
  if not supportedBuild() then r.status = "unsupported_build"; return r end
  if type(mapId) ~= "number" or math.type(mapId) ~= "integer" then r.status = "no_map"; return r end
  local slot = revealSlot(mapId)
  if type(slot) ~= "number" then r.status = slot; return r end
  r.slot = slot
  local okRoot, root = pcall(function() return memory.u64[REVEAL_ROOT_PTR] end)
  if not okRoot then r.status = "read_failed"; return r end
  if root ~= REVEAL_ROOT then r.status = "root_unexpected"; return r end
  local ok, a, b = pcall(function() return revealRecordHex(slot), revealRecordHex(slot) end)
  if not ok then r.status = "read_failed"; return r end
  if a ~= b then r.status = "unstable"; return r end
  r.status = "ok"
  r.record = a
  r.stable = true
  r.observedClockUs = safeCall(event.getClock)
  return r
end

--Inside revealState's closure. Fixed-address reads only; savedState remains unchanged.
local FLAGS, BOUND, DESC, SCRATCH = 0x2B45A00, 0x2B476B8, 0x2B45A50, 0x2B45978
local SAVE_PTR, SAVE, CURRENT_MAP = 0x2EBF190, 0x21B290C, 0x21654C4
local SETTLE_US, CONFIRM_US = 1000000, 500000
local seenMap, seenJumps, settleUntil, readyMeta, readyAt

local function resetReady() readyMeta, readyAt = nil, nil end
local function same(a, b)
  if not a or not b or #a ~= #b then return false end
  for i = 1, #a do if a[i] ~= b[i] then return false end end
  return true
end
local function metadata()
  return { memory.u64[SAVE_PTR], memory.u32[SAVE], memory.u32[FLAGS], memory.u32[BOUND],
           memory.u64[DESC], memory.u16[DESC + 8], memory.u16[DESC + 10],
           memory.u16[DESC + 12], memory.u16[DESC + 14], memory.u16[DESC + 16],
           memory.u32[CURRENT_MAP] }
end
local function valid(m, mapId)
  if m[1] ~= SAVE or m[2] ~= 0x05071901 then return "save_not_ready" end
  if (m[3] & 1) == 0 then return "inactive" end
  if m[4] ~= mapId or m[11] ~= mapId then return "map_mismatch" end
  if m[5] ~= SCRATCH then return "pointer_unexpected" end
  local w, h, cols, rows, stride = m[6], m[7], m[8], m[9], m[10]
  if w < 64 or h < 64 or w > 4096 or h > 4096 or w % 64 ~= 0 or h % 64 ~= 0
     or cols ~= w // 64 or rows ~= h // 64 or cols % 8 ~= 0
     or stride ~= (cols >> 3) or stride < 1 or rows * stride > 32 then return "descriptor_invalid" end
end
local function bitmap()
  local words = {}
  for i = 0, 28, 4 do
    local v = memory.u32[SCRATCH + i]
    words[#words + 1] = string.format("%02x%02x%02x%02x", v & 255, (v >> 8) & 255,
                                     (v >> 16) & 255, (v >> 24) & 255)
  end
  return table.concat(words)
end
local function sampleLive(mapId)
  local now = safeCall(event.getClock)
  if type(now) ~= "number" then resetReady(); return nil, "clock_unavailable" end
  local a = metadata()
  local why = valid(a, mapId)
  if why then seenMap = nil; resetReady(); return nil, why end
  if seenMap ~= mapId or seenJumps ~= mapJumps or (readyAt and now < readyAt) then
    seenMap, seenJumps, settleUntil = mapId, mapJumps, now + SETTLE_US
    resetReady()
  end
  if now < settleUntil then return nil, "transition" end
  local first = bitmap()
  --Recheck active/binding/readiness between copies, then enclose the second copy as well.
  local b = metadata()
  local invalidB = valid(b, mapId)
  if invalidB then seenMap = nil end
  if not same(a, b) or invalidB then resetReady(); return nil, "unstable" end
  local second = bitmap()
  local c = metadata()
  local invalidC = valid(c, mapId)
  if invalidC then seenMap = nil end
  if not same(b, c) or invalidC or first ~= second then
    resetReady(); return nil, "unstable"
  end
  --The native no-record initializer fills 128 bytes with FF. A fully explored record is indistinguishable:
  --omit either rather than ever publishing this ambiguous all-ones source. Saved bytes remain available.
  if first:sub(1, a[9] * a[10] * 2) == string.rep("ff", a[9] * a[10]) then resetReady(); return nil, "all_ones_ambiguous" end
  if not same(a, readyMeta) then readyMeta, readyAt = a, now; return nil, "confirming" end
  if now - readyAt < CONFIRM_US then return nil, "confirming" end
  return { record = first, source = "live", w = a[6], h = a[7], stride = a[10], map = mapId }, "ok"
end

return function(mapId)
  local r = savedState(mapId)
  if r.status ~= "ok" then
    seenMap, seenJumps, settleUntil = nil, nil, nil
    resetReady()
    return r
  end
  local ok, live, reason = pcall(sampleLive, mapId)
  if not ok then resetReady(); r.liveStatus = "read_failed"
  else r.live, r.liveStatus = live, reason end
  return r
end

end)()

--A mod's config file (in the game folder, written by that mod) is a Lua chunk returning tables. It is run, as text, with
--an empty environment: no globals, no I/O, no game access. That is not a sandbox against a hostile file (a loop could
--still hang it); it is only used for the Insurgent's Manifesto's own config, which the player installed.
local function readConfigTable(path)
  local fn = loadfile(path, "t", {})
  if not fn then return nil end
  local ok, states, params = pcall(fn)
  if not ok or type(states) ~= "table" then return nil end
  return { states = states, parameters = params }
end

--Which loaders and optional mods are present (file presence only).
local function modInventory()
  local mods = {}
  for _, m in ipairs(MOD_FILES) do
    local size = fileSize(m.path)
    mods[#mods + 1] = { id = m.id, kind = m.kind,
      file_present = { value = size ~= nil, size = size, observedClockUs = safeCall(event.getClock), source = "lua:io" } }
  end
  return mods
end

local function pluginInventory()
  local list = {}
  if type(game) == "table" and type(game.plugins) == "table" then
    for id, p in pairs(game.plugins) do
      local v = nil
      if type(p) == "table" and p.getLibVersion then
        local ok, ver = pcall(p.getLibVersion)
        if ok and type(ver) == "table" then v = table.concat({ ver[1] or ver.major, ver[2] or ver.minor, ver[3] or ver.patch }, ".") end
      end
      list[#list + 1] = { id = id, version = v, experimental = type(p) == "table" and p.experimental or nil }
    end
    table.sort(list, function(a, b) return a.id < b.id end)
  end
  --nil (omitted) rather than an empty table, which the encoder cannot tell apart from an object
  return #list > 0 and list or nil
end

--Capability flags: "on" = implemented from a documented/verified API, "probe" = present but its
--meaning still needs a live check, "off" = not implemented. The companion shows only "on" data as live.
local function capabilities()
  local caps = {
    ["map.id"] = (call and call.getCurrentMap) and "probe" or "off",
    ["map.index"] = (call and call.getCurrentMapIndex) and "probe" or "off",
    ["map.jumpEvent"] = "probe",
    ["map.position"] = "probe",
    ["map.transform"] = "off",
    ["map.reveal"] = "on",
    ["bpack.read"] = (game and game.bpack) and "probe" or "off",
    ["save.read"] = (save and save.u32) and "probe" or "off",
    ["party"] = "on",
    ["target"] = "probe",
    ["flags"] = "probe",
    ["quests"] = "probe",
    ["battle"] = "probe",
    ["gambits"] = "probe",
    ["gambits.write"] = "probe",
    ["build"] = "probe",
    ["build.owned"] = "probe",
    ["inventory"] = "probe",
    ["primer"] = "off",
    ["licenses"] = "off",
    ["actions"] = "off",
  }
  return caps
end

local function mapState()
  return {
    mapId = safeCall(call and call.getCurrentMap),
    mapIndex = safeCall(call and call.getCurrentMapIndex),
    previousMapId = safeCall(call and call.getPreviousMap),
    previousMapIndex = safeCall(call and call.getPreviousMapIndex),
    jumps = mapJumps,
    lastJump = lastJump,
    position = nil,
  }
end

local staticHello = nil

local function snapshot()
  return {
    hello = staticHello,
    session = {
      loaderVersion = staticHello and staticHello.loader.lua or loaderVersion(),
      clockUs = safeCall(event.getClock),
      wallTime = os.time(),
    },
    map = mapState(),
  }
end


--Menu-map position (statically traced, 21 Oct 2026). The field update 0x3C6000 runs while [0x2B45A00] & 1 and copies
--the controlled actor's x, y, z (w = 1.0) into MAP_POS. MAP_ORIGIN is the current sheet's NPS2 (x0, z0); MAP_EXTENT its
--width, depth. Sheet pixel = 4 * (x - x0), 4 * (z - z0). All are static globals in the image, so reading them cannot
--fault; values are only as fresh as the last update.
local MAP_FLAGS = 0x2B45A00
local MAP_POS = 0x2B45810
local MAP_ORIGIN = 0x2B45800
local MAP_EXTENT = 0x2B45808

local function f32(addr)
  local v = memory.u32[addr]
  return (string.unpack("<f", string.pack("<I4", v)))
end

local function readPosition()
  return { f32(MAP_POS), f32(MAP_POS + 4), f32(MAP_POS + 8), f32(MAP_POS + 12),
           f32(MAP_ORIGIN), f32(MAP_ORIGIN + 4), f32(MAP_EXTENT), f32(MAP_EXTENT + 4),
           memory.u32[MAP_FLAGS] }
end

local function round3(v) return math.floor(v * 1000 + 0.5) / 1000 end

--Status values: ok, unsupported_build, read_failed, unstable, navimap_inactive, bad_w.
local function positionState()
  local r = { contract = "ffxii.mappos.v1" }
  if not supportedBuild() then r.status = "unsupported_build"; return r end
  local ok, a, b = pcall(function() return readPosition(), readPosition() end)
  if not ok then r.status = "read_failed"; return r end
  for i = 1, #a do
    if a[i] ~= b[i] then r.status = "unstable"; return r end
  end
  r.navimapActive = (a[9] & 1) == 1
  if not r.navimapActive then r.status = "navimap_inactive"; return r end
  if a[4] ~= 1.0 then r.status = "bad_w"; return r end
  r.status = "ok"
  r.x, r.y, r.z = round3(a[1]), round3(a[2]), round3(a[3])
  r.originX, r.originZ, r.extentX, r.extentZ = round3(a[5]), round3(a[6]), round3(a[7]), round3(a[8])
  r.observedClockUs = safeCall(event.getClock)
  return r
end

--Stored gambit sets: [0x2EBF190] = save base + 0x4E68C (static); member m's three 0x34-byte sets at +0x5B10 + m*0x9C.
--Set = 12 x {u16 condition, u16 action} (0xFFFFFFFF empty), +0x30 u16 enabled bits, +0x32 u8 count. The Gambits menu
--commits here when the player leaves the Gambits screen. Read every GAMBITS_MS and on map jumps, twice, change-only.
local GAMBIT_SAVE_PTR = 0x2EBF190
local GAMBIT_BASE = 0x2164280 + 0x4E68C
--0.13.0: a save is loaded when [0x2EBF190] points at the save region AND the region starts with the game's own marker
--0x05071901 (the game checks it at 0x233C4D). The pointer alone is set at startup too (0x238A92), before any save
--loads (Map_Icons_Flags_2026-10-22.md), so on its own it let the title screen through with leftover values.
local function saveLoaded()
  return memory.u64[GAMBIT_SAVE_PTR] == GAMBIT_BASE and memory.u32[GAMBIT_BASE] == 0x05071901
end
local GAMBIT_SETS = 0x5B10
local GAMBIT_STRIDE = 0x9C
local GAMBIT_MEMBERS = 6
local GAMBITS_MS = 2000
local BUILD_RECORD, BUILD_LIC_OFF, BUILD_LIC_BYTES = 0x1C8, 0x194, 0x2F   --member record, licence bits (party build)
local LIC_DESC_PTR = 0x2EBF038                                            --live licence-node table descriptor
local gambits, gambitsClock, forceGambits = nil, nil, true

local function readGambitSets()
  local out = {}
  for m = 0, GAMBIT_MEMBERS - 1 do
    local a, t = GAMBIT_BASE + GAMBIT_SETS + m * GAMBIT_STRIDE, {}
    for i = 0, GAMBIT_STRIDE - 4, 4 do
      local v = memory.u32[a + i]
      t[#t + 1] = string.format("%02x%02x%02x%02x", v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF)
    end
    out[#out + 1] = table.concat(t)
  end
  return table.concat(out, ",")
end

--0.10.0: each member's active set index (u32 at P + 0x7370 + m*4, 0..2), "k,k,k,k,k,k".
local function readActiveSets()
  local out = {}
  for m = 0, GAMBIT_MEMBERS - 1 do out[#out + 1] = tostring(memory.u32[GAMBIT_BASE + 0x7370 + m * 4]) end
  return table.concat(out, ",")
end

--Status values: ok, unsupported_build, save_not_ready, read_failed, unstable.
local function gambitState()
  local now = safeCall(event.getClock) or 0
  if not forceGambits and gambits and not forceTable.due(gambitsClock, now, 4, 1, (GAMBITS_MS + 1500) * 1000) then return gambits end
  forceGambits = false
  gambitsClock = now
  local r = { contract = "ffxii.gambits.v1" }
  if not supportedBuild() then r.status = "unsupported_build"
  else
    local okp, ptr = pcall(function() return memory.u64[GAMBIT_SAVE_PTR] end)
    if not okp then r.status = "read_failed"
    elseif ptr ~= GAMBIT_BASE or not saveLoaded() then r.status = "save_not_ready"
    else
      local ok1, a = pcall(readGambitSets)
      local okA, act1 = pcall(readActiveSets)
      local ok2, b = pcall(readGambitSets)
      local okB, act2 = pcall(readActiveSets)
      if not ok1 or not ok2 or not okA or not okB then r.status = "read_failed"
      elseif a ~= b or act1 ~= act2 then r.status = "unstable"
      else r.status = "ok"; r.sets = a; r.active = act1; r.stable = true; r.observedClockUs = now end
    end
  end
  gambits = r
  return r
end

--Gambit editor (0.5.0). OFF unless the file EDIT_ARM exists (created by the companion app's editor switch, and
--read when the game starts). The companion drops one command file naming a member, set, slot, the
--slot's expected current value and the change. On the timer thread the logger consumes it (deleted before anything else,
--so it is never replayed) and checks every gate; then, on the next synchronous onFlip (the game's frame thread 560), it
--re-checks, writes the stored set and, if that set is the active one, the runtime AI list exactly as the game's own
--rebuild 0x327DE0 builds it from the live condition records, reads both back and restores the old bytes on a mismatch.
--Runtime-list order: entries that stay valid keep their place; the count byte is first lowered to that unchanged prefix,
--the new entries are written past it, and the final count is written last, so the list is never read half-written.
--0.5.3 (Gambit_Editor_Deadlock_2026-10-21.md): Lua Loader 1.7.1 deadlocks if registerEventSync runs from a callback
--while the frame thread dispatches this script's sync onFlip (dispatch holds the sync-table mutex +0xd8 and waits for the
--Lua mutex +0xe0; a callback holds +0xe0 and registerEventSync waits for +0xd8). So the frame hook is ONE permanent sync
--onFlip registered at script load, and only when EDIT_ARM already exists then (arming needs a game restart); nothing
--registers or unregisters an event after load. Not armed at load = no hook, no per-frame cost.
local EDIT_DIR = BASE_DIR .. "cmd/"
local EDIT_CMD = EDIT_DIR .. "gambit_cmd.txt"
local EDIT_ARM = EDIT_DIR .. "ARMED"
local EDIT_LOG = EDIT_DIR .. "gambit_log.jsonl"
local EDIT_CONTRACT = "ffxii.gambitedit.v1"
local CMD_CONTRACT = "ffxii.gambitcmd.v1"
local CMD_MAX_AGE_S = 120
local APPLY_TIMEOUT_US = 3000000
local PERSIST_CHECK_US = 2500000
local SET_BYTES = 0x34
local SET_INDEX = 0x7370
local RUNTIME_LIST = 0x2089378
local RUNTIME_STRIDE = 0x170
local RUNTIME_ENTRY = 0x18
--0x327DE0: record = (([0x1F83530] + [[0x2EBF0D8]+0xC]) + (cond - 0x6000) * u16[[0x2EBF0D8]+8]) truncated to 32 bits
local COND_DESC_PTR = 0x2EBF0D8
local PACK_BASE_PTR = 0x1F83530
--runtime entry offset (from the list base, entry j adds j*0x18), condition record offset, record width; all stored as u16
local RT_FIELDS = { { 0x24, 0x0E, 2 }, { 0x26, 0x10, 1 }, { 0x28, 0x08, 2 }, { 0x2A, 0x18, 2 }, { 0x2C, 0x11, 1 },
                    { 0x2E, 0x0A, 2 }, { 0x30, 0x1A, 2 }, { 0x32, 0x12, 1 }, { 0x34, 0x0C, 2 }, { 0x36, 0x1C, 2 } }
local RT_OFFS = { 0x20 }
for _, f in ipairs(RT_FIELDS) do RT_OFFS[#RT_OFFS + 1] = f[1] end

local editSeen, editPending, editLast, editNewSet, editLogQueue = {}, nil, nil, nil, {}
local editHooked = false   --true once the load-time frame hook is registered (armed at game start)

--Cache only the status probe. Every incoming edit still forces a fresh ARMED check below.
local fileExists = (function()
  local armObserved
  return function(path, refresh)
    if path == EDIT_ARM and not refresh and armObserved ~= nil then return armObserved end
    local f = io.open(path, "rb")
    local present = f ~= nil
    if f then f:close() end
    if path == EDIT_ARM then armObserved = present end
    return present
  end
end)()

local function setAddr(m, k) return GAMBIT_BASE + GAMBIT_SETS + m * GAMBIT_STRIDE + k * SET_BYTES end
local function runtimeAddr(m) return RUNTIME_LIST + m * RUNTIME_STRIDE end

local function readSet(m, k)
  local a, s = setAddr(m, k), { cond = {}, act = {} }
  for i = 0, 11 do s.cond[i] = memory.u16[a + 4 * i]; s.act[i] = memory.u16[a + 4 * i + 2] end
  s.bits = memory.u16[a + 0x30]
  return s
end

local function copySet(s)
  local c = { cond = {}, act = {}, bits = s.bits }
  for i = 0, 11 do c.cond[i] = s.cond[i]; c.act[i] = s.act[i] end
  return c
end

local function sameSet(a, b)
  if a.bits ~= b.bits then return false end
  for i = 0, 11 do if a.cond[i] ~= b.cond[i] or a.act[i] ~= b.act[i] then return false end end
  return true
end

--0.5.1: on the Thor (21 Oct, first approved try) the live table was unavailable in the field, so the reason is now
--reported (data.gambitEdit.table) and a copy of the live records, taken whenever the game has them, is used when it
--does not; the model check (live runtime list == replica) still guards every write. Copy refresh: CACHE_MS.
local CACHE_MS = 10000
local condCache, condCacheClock, condDiag = nil, nil, { source = "none", why = "not_read" }

local function hex(v) return v and string.format("%x", v) or nil end

local function condTableLive()
  local d = {}
  local desc, base = memory.u64[COND_DESC_PTR], memory.u64[PACK_BASE_PTR]
  d.desc, d.base = hex(desc), hex(base)
  if desc == nil or desc == 0 then d.why = "desc_null"; return nil, d end
  --0.5.2: base 0 is normal on the Thor (21 Oct, 0.5.1 diagnostics: desc set, base 0); 0x20E600 then returns the 32-bit
  --data offset itself, i.e. the record address, and the game reads the records there. Done the same way here.
  if base == nil then d.why = "base_unreadable"; return nil, d end
  if desc < 0x10000 or desc > 0x7FFFFFFFFFFF then d.why = "desc_range"; return nil, d end
  local stride, off, count = memory.u16[desc + 8], memory.u32[desc + 0xC], memory.u32[desc + 4]
  d.stride, d.off, d.count = stride, hex(off), count
  if stride < 0x1E or stride > 0x100 then d.why = "stride"; return nil, d end
  if count < 1 or count > 1024 then d.why = "count"; return nil, d end   --count at +4: the 0x238880 loop bound
  local t = { source = "live", stride = stride, count = count }
  local first = (base + off) & 0xFFFFFFFF
  d.first = hex(first)
  if first < 0x10000 then d.why = "records_null"; return nil, d end
  t.field = function(c, o, w)
    local r = (first + (c - 0x6000) * stride) & 0xFFFFFFFF
    return w == 1 and memory.u8[r + o] or memory.u16[r + o]
  end
  t.first = first
  return t, d
end

local function copyTable(t)
  local recs = {}
  for c = 0, t.count - 1 do
    local r, b = (t.first + c * t.stride) & 0xFFFFFFFF, {}
    for o = 0, 0x1F, 4 do
      local v = memory.u32[r + o]
      b[o], b[o + 1], b[o + 2], b[o + 3] = v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF
    end
    recs[c] = b
  end
  return { source = "cache", stride = t.stride, count = t.count, recs = recs,
           field = function(c, o, w)
             local b = recs[c - 0x6000]
             return w == 1 and b[o] or (b[o] | (b[o + 1] << 8))
           end }
end

--Live records when the game has them, else this session's copy, else nil. refresh (timer thread only): renew the
--copy every CACHE_MS from the live records.
local function condTable(refresh)
  local ok, t, d = pcall(condTableLive)
  if not ok then t, d = nil, { why = "read_failed" } end
  local now = safeCall(event.getClock) or 0
  if t then
    if refresh and not condCache or not condCacheClock or now - condCacheClock >= CACHE_MS * 1000 then
      local okc, c = pcall(copyTable, t)
      if okc then condCache, condCacheClock = c, now end
    end
    d.source = "live"
  else
    d.source = condCache and "cache" or "none"
  end
  d.cachedAgoMs = condCacheClock and math.floor((now - condCacheClock) / 1000) or nil
  condDiag = d
  return t or condCache
end

--The entries 0x327DE0 writes for one stored set: enabled, non-empty slots in order, action + condition record fields.
local function replicaEntries(s, t)
  local out = {}
  for i = 0, 11 do
    if (s.bits >> i) & 1 == 1 and s.cond[i] ~= 0xFFFF and s.act[i] ~= 0xFFFF then
      local e = { s.act[i] }
      for _, f in ipairs(RT_FIELDS) do e[#e + 1] = t.field(s.cond[i], f[2], f[3]) end
      out[#out + 1] = e
    end
  end
  return out
end

local function entryEquals(rt, j, e)
  for n, off in ipairs(RT_OFFS) do
    if memory.u16[rt + j * RUNTIME_ENTRY + off] ~= e[n] then return false end
  end
  return true
end

local function runtimeMatches(rt, entries)
  if memory.u8[rt] ~= #entries then return false end
  for j = 1, #entries do if not entryEquals(rt, j - 1, entries[j]) then return false end end
  return true
end

--count byte + every u16 of the 12 entry slots, for an exact restore (bytes past the count included)
local function saveRuntime(rt)
  local w = {}
  for o = 0x20, 0x20 + 12 * RUNTIME_ENTRY - 2, 2 do w[o] = memory.u16[rt + o] end
  return { count = memory.u8[rt], words = w }
end

local function sameRuntime(rt, saved)
  if memory.u8[rt] ~= saved.count then return false end
  for o, v in pairs(saved.words) do if memory.u16[rt + o] ~= v then return false end end
  return true
end

--Lower the count to the prefix that stays valid, write the rest, then the final count (see the header comment).
local function writeRuntime(rt, entries)
  local old, p = memory.u8[rt], 0
  while p < old and p < #entries and entryEquals(rt, p, entries[p + 1]) do p = p + 1 end
  if p == old and p == #entries then return end
  if p < old then memory.u8[rt] = p end
  for j = p, #entries - 1 do
    for n, off in ipairs(RT_OFFS) do memory.u16[rt + j * RUNTIME_ENTRY + off] = entries[j + 1][n] end
  end
  if memory.u8[rt] ~= #entries then memory.u8[rt] = #entries end
end

local function restoreRuntime(rt, saved)
  local cur, p = memory.u8[rt], 0
  local function entrySame(j)
    for _, off in ipairs(RT_OFFS) do
      if memory.u16[rt + j * RUNTIME_ENTRY + off] ~= saved.words[j * RUNTIME_ENTRY + off] then return false end
    end
    return true
  end
  while p < cur and p < saved.count and entrySame(p) do p = p + 1 end
  if p < cur then memory.u8[rt] = p end
  for o, v in pairs(saved.words) do if memory.u16[rt + o] ~= v then memory.u16[rt + o] = v end end
  memory.u8[rt] = saved.count
end

local function writeSetSlot(m, k, slot, old, new)
  local a = setAddr(m, k)
  if new.cond[slot] ~= old.cond[slot] then memory.u16[a + 4 * slot] = new.cond[slot] end
  if new.act[slot] ~= old.act[slot] then memory.u16[a + 4 * slot + 2] = new.act[slot] end
  if new.bits ~= old.bits then memory.u16[a + 0x30] = new.bits end
end

--Ordinary party actions only. 0x6F..0x7B lie inside the item range but are reserved records, not items, so they are
--never written (supported pack layout; an action-table mod would need a fresh check).
local function validAction(a)
  if a >= 0x6F and a <= 0x7B then return false end
  return a <= 0x50 or (a >= 0x52 and a <= 0x91) or a == 0x96 or (a >= 0x9E and a <= 0x9E + 0x17)
end

--0.11.0 hardening (Secure_Loader_Design): the command file lives in shared storage, so its size, fields and the replay
--memory are all bounded.
local CMD_MAX_BYTES, SEEN_MAX = 4096, 256
local CMD_FIELDS = { id = true, issued = true, member = true, set = true, slot = true, op = true, cond = true, action = true,
                     bits = true, newCond = true, newAction = true, from = true, stored = true }
local editSeenOrder = {}

local function parseCommand(text)
  local lines = {}
  for line in text:gmatch("[^\r\n]+") do lines[#lines + 1] = line end
  if lines[1] ~= CMD_CONTRACT then return nil, "bad_contract" end
  local c = {}
  for i = 2, #lines do
    local k, v = lines[i]:match("^(%a+)=([%w%-]+)$")
    if not k then return nil, "bad_line" end
    if not CMD_FIELDS[k] then return nil, "unknown_field" end   --0.11.0: exact grammar: known fields, each once
    if c[k] ~= nil then return nil, "duplicate_field" end
    c[k] = v
  end
  local function dec(s, lo, hi)
    local n = s and s:match("^%d+$") and tonumber(s)
    if n and n >= lo and n <= hi then return n end
  end
  local function hx(s) return s and s:match("^%x%x%x%x$") and tonumber(s, 16) or nil end
  local cmd = { id = c.id, op = c.op, issued = dec(c.issued, 0, 1 << 40), member = dec(c.member, 0, GAMBIT_MEMBERS - 1),
                set = dec(c.set, 0, 2), slot = dec(c.slot, 0, 11), cond = hx(c.cond), action = hx(c.action),
                bits = hx(c.bits), newCond = hx(c.newCond), newAction = hx(c.newAction),
                from = dec(c.from, 0, 2), stored = c.stored and c.stored:lower() }
  if not (cmd.id and cmd.id:match("^[%w%-]+$") and #cmd.id >= 8 and #cmd.id <= 40) then return nil, "bad_id" end
  if cmd.op == "setactive" then   --0.10.0: switch the member's active set (Action_IDs_and_Set_Switch_2026-10-22, Part B)
    for _, k in ipairs({ "issued", "member", "set", "from" }) do
      if cmd[k] == nil then return nil, "missing_" .. k, cmd end
    end
    if not (cmd.stored and #cmd.stored == GAMBIT_STRIDE * 2 and cmd.stored:match("^%x+$")) then return nil, "missing_stored", cmd end
    return cmd
  end
  if cmd.op ~= "enable" and cmd.op ~= "disable" and cmd.op ~= "set" and cmd.op ~= "setslot" then return nil, "bad_op", cmd end
  for _, k in ipairs({ "issued", "member", "set", "slot", "cond", "action", "bits" }) do
    if cmd[k] == nil then return nil, "missing_" .. k, cmd end
  end
  if (cmd.op == "set" or cmd.op == "setslot") and (cmd.newCond == nil or cmd.newAction == nil) then return nil, "missing_new", cmd end
  return cmd
end

--Insurgent's Manifesto can switch the game to one 36-slot gambit list, which the editor does not support. Without
--the Manifesto the game has its normal three sets (false); with it, its config must say the option is off.
local function manifestoSingleList()
  if not fileSize("scripts/TheInsurgentsManifesto.lua") then return false end
  local cfg = readConfigTable("scripts/config/TheInsurgentsManifestoConfig.lua")
  if not cfg or type(cfg.states) ~= "table" then return nil end
  return cfg.states.thirtySixGambitsAndOneSet
end

--Every gate that does not need the frame thread. Returns the planned edit or nil, reason.
--0.5.6 setslot gate, read live: the condition must be an owned gambit (owned lists, probe) and a magick/technick must be
--owned by the party AND granted by one of this member's licences (licence bits + live node table). Items and Attack pass
--(item stock is not read).
local ownedReader   --= readOwned, assigned where the party-build block defines it (declared later in this file)

local function setslotAllowed(m, cond, action)
  local owned = ownedReader and ownedReader()
  if not owned then return false, "ownership_unknown" end
  local function inList(list, id)   --4-hex-digit entries: match only on entry boundaries
    for p = 1, #list, 4 do if tonumber(list:sub(p, p + 3), 16) == id then return true end end
    return false
  end
  if not inList(owned.gambits, cond) then return false, "condition_not_owned" end
  local magick, technick = action <= 0x50, action >= 0x9E and action <= 0x9E + 0x17
  if not magick and not technick then return true end
  if magick and not inList(owned.magicks, 0x3000 + action) then return false, "action_not_owned" end
  if technick and not inList(owned.technicks, 0x4000 + action - 0x9E) then return false, "action_not_owned" end
  local desc, base = memory.u64[LIC_DESC_PTR], memory.u64[PACK_BASE_PTR]
  if desc == nil or desc == 0 or base == nil then return false, "licences_unknown" end
  local count, stride = memory.u16[desc + 4], memory.u16[desc + 8]
  local first = (base + memory.u32[desc + 0xC]) & 0xFFFFFFFF
  if count < 1 or count > 1024 or stride < 24 or stride > 64 or first < 0x10000 then return false, "licences_unknown" end
  local rec = GAMBIT_BASE + 8 + m * BUILD_RECORD
  for n = 0, count - 1 do
    local a = (first + n * stride) & 0xFFFFFFFF
    local ty = memory.u8[a + 5]
    if (magick and ty >= 0x09 and ty <= 0x0D) or (technick and ty == 0x0E) then
      for g = 0, 7 do
        if memory.u16[a + 8 + 2 * g] == action then
          if (n >> 3) < BUILD_LIC_BYTES and (memory.u8[rec + BUILD_LIC_OFF + (n >> 3)] >> (n & 7)) & 1 == 1 then return true end
        end
      end
    end
  end
  return false, "action_not_licensed"
end

local function planEdit(cmd)
  if not supportedBuild() then return nil, "unsupported_build" end
  if not saveLoaded() then return nil, "save_not_ready" end
  if manifestoSingleList() ~= false then return nil, "manifesto_36_slot_on_or_unknown" end
  if memory.u32[MAP_FLAGS] & 1 ~= 1 then return nil, "not_in_field" end
  local t = condTable()
  if not t then return nil, "condition_table_unavailable", { table = condDiag } end
  local m, k, i = cmd.member, cmd.set, cmd.slot
  local active = memory.u32[GAMBIT_BASE + SET_INDEX + m * 4]
  if active > 2 then return nil, "bad_active_set" end
  local cur = readSet(m, k)
  if cur.cond[i] ~= cmd.cond or cur.act[i] ~= cmd.action or cur.bits ~= cmd.bits then return nil, "stale_expected" end
  local new = copySet(cur)
  if cmd.op == "setslot" then   --0.5.6: condition + action + ON in one write; may fill an empty slot inside the set's count
    if i >= memory.u8[setAddr(m, k) + 0x32] then return nil, "slot_locked" end
    if cmd.newCond < 0x6000 or cmd.newCond >= 0x6000 + t.count then return nil, "bad_condition" end
    if not validAction(cmd.newAction) then return nil, "bad_action" end
    local okU, why = setslotAllowed(m, cmd.newCond, cmd.newAction)
    if not okU then return nil, why end
    new.cond[i], new.act[i], new.bits = cmd.newCond, cmd.newAction, cur.bits | (1 << i)
    if sameSet(new, cur) then return nil, "no_change" end
    if not runtimeMatches(runtimeAddr(m), replicaEntries(readSet(m, active), t)) then return nil, "runtime_mismatch" end
    return { cmd = cmd, active = active, old = cur, new = new }
  end
  if cur.cond[i] == 0xFFFF or cur.act[i] == 0xFFFF then return nil, "empty_slot" end
  if cmd.op == "enable" then
    if (cur.bits >> i) & 1 == 1 then return nil, "already_on" end
    new.bits = cur.bits | (1 << i)
  elseif cmd.op == "disable" then
    if (cur.bits >> i) & 1 == 0 then return nil, "already_off" end
    new.bits = cur.bits & ~(1 << i) & 0xFFFF
  else
    if cmd.newCond < 0x6000 or cmd.newCond >= 0x6000 + t.count then return nil, "bad_condition" end
    if not validAction(cmd.newAction) then return nil, "bad_action" end
    new.cond[i], new.act[i] = cmd.newCond, cmd.newAction
  end
  --model check: the game's current list must be exactly what the replica builds from the active set
  if not runtimeMatches(runtimeAddr(m), replicaEntries(readSet(m, active), t)) then return nil, "runtime_mismatch" end
  return { cmd = cmd, active = active, old = cur, new = new }
end

--0.10.0 setactive (Action_IDs_and_Set_Switch_2026-10-22, Part B). The game's LT/RT in the Gambits menu writes only the
--active index; leaving the menu rebuilds the runtime list from the now-active stored set (0x328140, same payload as
--0x327DE0). From the field this does the same: index + runtime rebuild, nothing else. The Gambits menu must be closed
--(its panels cache the sets and would overwrite the change), the live condition table must be readable, and the stored
--sets, the active index and the runtime list must all be exactly what the companion saw and the model expects.
local GAMBIT_MENU_PANELS = { 0x2CA96E0, 0x2CA96E8, 0x2CA96F0, 0x2CA96F8, 0x2CA9700, 0x2CA9708 }

local function storedHex(m)
  local a, out = GAMBIT_BASE + GAMBIT_SETS + m * GAMBIT_STRIDE, {}
  for i = 0, GAMBIT_STRIDE - 1 do out[#out + 1] = string.format("%02x", memory.u8[a + i]) end
  return table.concat(out)
end

local function planSetActive(cmd)
  if not supportedBuild() then return nil, "unsupported_build" end
  if not saveLoaded() then return nil, "save_not_ready" end
  if manifestoSingleList() ~= false then return nil, "manifesto_36_slot_on_or_unknown" end
  if memory.u32[MAP_FLAGS] & 1 ~= 1 then return nil, "not_in_field" end
  for _, a in ipairs(GAMBIT_MENU_PANELS) do
    if memory.u64[a] ~= 0 then return nil, "gambits_menu_open" end
  end
  local t = condTable()
  if not t or t.source ~= "live" then return nil, "condition_table_unavailable", { table = condDiag } end
  local m, target = cmd.member, cmd.set
  local active = memory.u32[GAMBIT_BASE + SET_INDEX + m * 4]
  if active > 2 then return nil, "bad_active_set" end
  if active ~= cmd.from or storedHex(m) ~= cmd.stored then return nil, "stale_expected" end
  if target == active then return { cmd = cmd, active = active, target = target, noop = true } end
  local new = readSet(m, target)
  local count = memory.u8[setAddr(m, target) + 0x32]
  if count > 12 then return nil, "bad_slot_count" end
  for i = 0, 11 do
    if (new.bits >> i) & 1 == 1 and new.cond[i] ~= 0xFFFF and new.act[i] ~= 0xFFFF then
      if i >= count then return nil, "slot_locked" end
      if new.cond[i] < 0x6000 or new.cond[i] >= 0x6000 + t.count then return nil, "bad_condition" end
      if not validAction(new.act[i]) then return nil, "bad_action" end
    end
  end
  if not runtimeMatches(runtimeAddr(m), replicaEntries(readSet(m, active), t)) then return nil, "runtime_mismatch" end
  return { cmd = cmd, active = active, target = target, entries = replicaEntries(new, t), stored = cmd.stored }
end

local function editResult(cmd, status, reason, extra)
  local r = { contract = EDIT_CONTRACT, id = cmd and cmd.id, op = cmd and cmd.op, member = cmd and cmd.member,
              set = cmd and cmd.set, slot = cmd and cmd.slot, status = status, reason = reason,
              clockUs = safeCall(event.getClock), wallTime = os.time() }
  for key, v in pairs(extra or {}) do r[key] = v end
  return r
end

local function finishEdit(r, newSet)
  editLast, editNewSet = r, newSet
  editLogQueue[#editLogQueue + 1] = r
  forceGambits = true
end

--Frame thread: count 0 first, then the index, then the entries, then the final count (the native rebuild leaves the
--header and the bytes past the count alone, and so does this). Read back; on a mismatch put the old index, entries and
--count back (count last) while still on this frame.
local function applySetActive(p)
  local ok, plan, why, extra = pcall(planSetActive, p.cmd)   --re-check every gate on this thread, right before writing
  if not ok then finishEdit(editResult(p.cmd, "rejected", "check_failed")); return end
  if not plan then finishEdit(editResult(p.cmd, "rejected", why, extra)); return end
  local m, k = plan.cmd.member, GAMBIT_BASE + SET_INDEX + plan.cmd.member * 4
  if plan.noop then finishEdit(editResult(plan.cmd, "applied", "already_active", { activeSet = plan.active, to = plan.target })); return end
  local rt = runtimeAddr(m)
  local savedRt = saveRuntime(rt)
  local info = { activeSet = plan.active, to = plan.target, runtimeCountBefore = savedRt.count }
  local wrote = pcall(function()
    memory.u8[rt] = 0
    memory.u32[k] = plan.target
    for j = 0, #plan.entries - 1 do
      for n, off in ipairs(RT_OFFS) do memory.u16[rt + j * RUNTIME_ENTRY + off] = plan.entries[j + 1][n] end
    end
    memory.u8[rt] = #plan.entries
  end)
  local good = wrote and pcall(function()
    assert(memory.u32[k] == plan.target and runtimeMatches(rt, plan.entries) and storedHex(m) == plan.stored)
  end)
  info.runtimeCountAfter = memory.u8[rt]
  if good then
    finishEdit(editResult(plan.cmd, "applied", nil, info), { m = m, k = plan.target, active = plan.target, setactive = true,
                                                              entries = plan.entries, stored = plan.stored })
    return
  end
  local restored = pcall(function()
    memory.u8[rt] = 0
    memory.u32[k] = plan.active
    for o, v in pairs(savedRt.words) do if memory.u16[rt + o] ~= v then memory.u16[rt + o] = v end end
    memory.u8[rt] = savedRt.count
    assert(memory.u32[k] == plan.active and sameRuntime(rt, savedRt))
  end)
  info.runtimeCountAfter = memory.u8[rt]
  finishEdit(editResult(plan.cmd, restored and "restored" or "restore_failed", wrote and "readback_mismatch" or "write_failed", info))
end

--Runs on the game's frame thread, from the permanent sync onFlip hook (registered at load when armed).
local function applyPendingEdit()
  local p = editPending
  editPending = nil
  if not p then return end
  local now = safeCall(event.getClock) or 0
  if now > p.deadline then finishEdit(editResult(p.cmd, "expired", "apply_late")); return end
  if p.cmd.op == "setactive" then applySetActive(p); return end
  local ok, plan, why, extra = pcall(planEdit, p.cmd)     --re-check every gate on this thread, right before writing
  if not ok then finishEdit(editResult(p.cmd, "rejected", "check_failed")); return end
  if not plan then finishEdit(editResult(p.cmd, "rejected", why, extra)); return end
  local m, k, i, rt = plan.cmd.member, plan.cmd.set, plan.cmd.slot, runtimeAddr(plan.cmd.member)
  local t = condTable()
  local savedRt = saveRuntime(rt)
  local entries = replicaEntries(plan.new, t)
  local info = { activeSet = plan.active, runtimeCountBefore = savedRt.count, tableSource = t.source,
                 before = string.format("%04x:%04x:%04x", plan.old.cond[i], plan.old.act[i], plan.old.bits),
                 after = string.format("%04x:%04x:%04x", plan.new.cond[i], plan.new.act[i], plan.new.bits) }
  local wrote = pcall(function()
    writeSetSlot(m, k, i, plan.old, plan.new)
    if k == plan.active then writeRuntime(rt, entries) end
  end)
  local good = wrote and pcall(function()
    assert(sameSet(readSet(m, k), plan.new))
    if k == plan.active then assert(runtimeMatches(rt, entries)) else assert(sameRuntime(rt, savedRt)) end
  end)
  info.runtimeCountAfter = memory.u8[rt]
  if good then finishEdit(editResult(plan.cmd, "applied", nil, info), { m = m, k = k, set = plan.new, active = plan.active }); return end
  local restored = pcall(function()
    writeSetSlot(m, k, i, readSet(m, k), plan.old)
    if not sameRuntime(rt, savedRt) then restoreRuntime(rt, savedRt) end
    assert(sameSet(readSet(m, k), plan.old) and sameRuntime(rt, savedRt))
  end)
  info.runtimeCountAfter = memory.u8[rt]
  finishEdit(editResult(plan.cmd, restored and "restored" or "restore_failed", wrote and "readback_mismatch" or "write_failed", info))
end

local function logEdit(r)
  local f = io.open(EDIT_LOG, "a")
  if f then f:write(encode(r), "\n"); f:close() end
end

--Timer thread, every poll: consume a command, check it, schedule the write; expire a write the frame never ran;
--confirm an applied edit is still in place PERSIST_CHECK_US later.
local function editTick()
  local now = safeCall(event.getClock) or 0
  if editPending and now > editPending.deadline then
    local cmd = editPending.cmd
    editPending = nil
    finishEdit(editResult(cmd, "expired", "no_frame"))
  end
  if editLast and editLast.status == "applied" and editLast.persisted == nil and editNewSet
     and now - (editLast.clockUs or now) >= PERSIST_CHECK_US then
    local ok, same = pcall(function()
      local n = editNewSet
      if n.setactive then   --index, stored bytes and runtime list must all still be what was written
        local t = condTable()
        return memory.u32[GAMBIT_BASE + SET_INDEX + n.m * 4] == n.active and storedHex(n.m) == n.stored
               and t ~= nil and t.source == "live" and runtimeMatches(runtimeAddr(n.m), n.entries)
      end
      local live = readSet(n.m, n.k)
      if not sameSet(live, n.set) then return false end
      if n.k ~= n.active then return true end
      return runtimeMatches(runtimeAddr(n.m), replicaEntries(n.set, condTable()))
    end)
    editLast.persisted = ok and same
    editLogQueue[#editLogQueue + 1] = editLast
  end
  for _, r in ipairs(editLogQueue) do logEdit(r) end
  editLogQueue = {}
  local f = io.open(EDIT_CMD, "r")
  if not f then return end
  local text = f:read(CMD_MAX_BYTES + 1) or ""
  f:close()
  if not os.remove(EDIT_CMD) then
    local r = editResult(nil, "rejected", "cannot_consume")
    if editLast == nil or editLast.reason ~= "cannot_consume" then finishEdit(r) end
    return
  end
  if #text > CMD_MAX_BYTES then finishEdit(editResult(nil, "rejected", "too_large")); return end
  local cmd, why, partial = parseCommand(text)
  if not cmd then finishEdit(editResult(partial, "rejected", why)); return end
  if editSeen[cmd.id] then finishEdit(editResult(cmd, "rejected", "duplicate_id")); return end
  editSeen[cmd.id] = true
  editSeenOrder[#editSeenOrder + 1] = cmd.id
  if #editSeenOrder > SEEN_MAX then editSeen[table.remove(editSeenOrder, 1)] = nil end
  if not fileExists(EDIT_ARM, true) then finishEdit(editResult(cmd, "rejected", "not_armed")); return end
  if not editHooked then finishEdit(editResult(cmd, "rejected", "editor_not_started")); return end
  local age = os.time() - cmd.issued
  if age > CMD_MAX_AGE_S or age < -30 then finishEdit(editResult(cmd, "rejected", "command_too_old")); return end
  if editPending then finishEdit(editResult(cmd, "rejected", "busy")); return end
  local ok, plan, reason, extra = pcall(cmd.op == "setactive" and planSetActive or planEdit, cmd)
  if not ok then finishEdit(editResult(cmd, "rejected", "check_failed")); return end
  if not plan then finishEdit(editResult(cmd, "rejected", reason, extra)); return end
  editPending = { cmd = cmd, deadline = now + APPLY_TIMEOUT_US }   --the load-time frame hook applies it (no registration here)
end

local tableClock = nil

local function editState()
  --condition-table diagnostics + copy refresh every 2 s (read-only)
  local now = safeCall(event.getClock) or 0
  if forceTable.due(tableClock, now, 4, 2, (GAMBITS_MS + 1500) * 1000) then
    tableClock = now
    fileExists(EDIT_ARM, true)  --refresh display status even without an edit command
    if supportedBuild() then pcall(condTable, true) end
  end
  return { contract = EDIT_CONTRACT, armed = fileExists(EDIT_ARM), hooked = editHooked,
           pending = editPending and editPending.cmd.id or nil, last = editLast, table = condDiag }
end

--Party build (0.5.4, read-only, Party_Build_054_2026-10-21.md). Member record = save + 8 + m*0x1C8:
--  +0x1C3/+0x1C4 the two jobs (0xFF none; game 0x32A7A0 "Has Job Assigned"), +0x190 u32 licence points, +0x194 licence
--  bits (node n = byte n>>3 bit n&7). Read every GAMBITS_MS,
--  twice. The live licence-node table ([0x2EBF038]: u16 count +4, u16 stride +8, u32 data +0xC; records at the same
--  32-bit base + offset as the condition table) is written once per session to LICNODES_FILE, and again if it changes,
--  because mods can extend it at run time.
local LICNODES_FILE = OUT_DIR .. "licence_nodes.json"
local SAVE_REGION = 0x2164280   --start of the static save region (owned gambit/magick/technick lists live in it)
local build, buildClock, licNodesKey, licNodesInfo = nil, nil, nil, nil
--0.5.12: live equipment table (battle-pack section 13 as loaded, descriptor [EQUIP_DESC_PTR], same layout as the
--licence table) written once per session to EQUIP_FILE, and again if it changes.
local EQUIP_DESC_PTR = 0x2EBF0F8
local EQUIP_FILE = OUT_DIR .. "equipment.json"
local equipKey, equipInfo = nil, nil

--0.5.5: owned gambits / magicks / technicks (PROBE: found in a live dump, Party_Build_054_2026-10-21.md, not yet tied
--to game code). Fixed-capacity u16 ID lists in the static save region, filled in acquisition order, 0xFFFF = empty.
local OWNED_LISTS = { { key = "gambits", off = 0xBEE4, cap = 256, base = 0x6000 },
                      { key = "technicks", off = 0xC0E4, cap = 24, base = 0x4000 },
                      { key = "magicks", off = 0xC114, cap = 81, base = 0x3000 } }

--{gambits = "6000 60a5 ..." (4 hex digits per owned ID, list order), ...}; nil, why if any entry is out of range or repeated.
local function readOwned()
  local out = {}
  for _, l in ipairs(OWNED_LISTS) do
    local ids, seen = {}, {}
    for i = 0, l.cap - 1 do
      local v = memory.u16[SAVE_REGION + l.off + 2 * i]
      if v ~= 0xFFFF then
        if v < l.base or v >= l.base + l.cap or seen[v] then return nil, "implausible_" .. l.key end
        seen[v] = true
        ids[#ids + 1] = string.format("%04x", v)
      end
    end
    out[l.key] = table.concat(ids)
  end
  return out
end

ownedReader = readOwned

--0.5.8: consumable stock (Item_Actions_Trace_2026-10-21.md, ESTABLISHED). 64 packed u16 indexed by item ID in the party
--block copy that the gambit item gate reads (getter 0x2FD8D0: low 10 bits = count, available = low10 - high6). Item ID =
--gambit action - 0x52 for item actions. Published raw ("0e00 0200 ..." without spaces); the companion does the arithmetic.
local STOCK_OFF, STOCK_ITEMS = 0x4748, 64

--0.5.11: equipment (Party_Vitals_Trace_2026-10-21.md, ESTABLISHED): five u16 per member record at M+0x50 (weapon,
--shield or ammo, head, body, accessory); IDs 0x1000+index into the equipment table, 0xFFFF = empty (game 0x31A630).
--"1076 10ce 10e9 1148 117e" (no spaces) per member, members comma-separated in member order 0..5.
local GEAR_OFF, GEAR_SLOTS = 0x50, 5

local function readGear()
  local out = {}
  for m = 0, GAMBIT_MEMBERS - 1 do
    local r, w = GAMBIT_BASE + 8 + m * BUILD_RECORD, {}
    for i = 0, GEAR_SLOTS - 1 do w[#w + 1] = string.format("%04x", memory.u16[r + GEAR_OFF + 2 * i]) end
    out[#out + 1] = table.concat(w)
  end
  return table.concat(out, ",")
end

--0.5.12: owned equipment (Gear_Optimize_Trace_2026-10-21.md, ESTABLISHED): 420 packed u16 at P+0x47C8 indexed by
--equipment ID - 0x1000; low 10 bits = total owned, high 6 = equipped; spare = total - equipped.
local EQUIP_STOCK_OFF, EQUIP_STOCK_ITEMS = 0x47C8, 420

local function readEquipStock()
  local w = {}
  for i = 0, EQUIP_STOCK_ITEMS - 1 do w[#w + 1] = string.format("%04x", memory.u16[GAMBIT_BASE + EQUIP_STOCK_OFF + 2 * i]) end
  return table.concat(w)
end

--0.5.14 (Licence_Rules_Trace_2026-10-21.md, ESTABLISHED): key items 0xC000+i = bit (i & 15) of u16 P+0x4F80+2*(i>>4)
--(Espers obtained: 0xC012..0xC01E; Second Board: 0xC01F); and records 0..6 (the Esper exclusivity scan 0x324280 checks
--M+0 bit 0x08 and the licence bits of these seven): "ff:licencebits" each. Read-only.
local KEY_OFF, KEY_WORDS, ROSTER_RECORDS = 0x4F80, 4, 7

local function readKeyItems()
  local w = {}
  for i = 0, KEY_WORDS - 1 do w[#w + 1] = string.format("%04x", memory.u16[GAMBIT_BASE + KEY_OFF + 2 * i]) end
  return table.concat(w)
end

local function readRoster()
  local out = {}
  for m = 0, ROSTER_RECORDS - 1 do
    local r, bits = GAMBIT_BASE + 8 + m * BUILD_RECORD, {}
    for i = 0, BUILD_LIC_BYTES - 1 do bits[#bits + 1] = string.format("%02x", memory.u8[r + BUILD_LIC_OFF + i]) end
    out[#out + 1] = string.format("%02x:", memory.u8[r]) .. table.concat(bits)
  end
  return table.concat(out, ",")
end

local function readStock()
  local w = {}
  for i = 0, STOCK_ITEMS - 1 do w[#w + 1] = string.format("%04x", memory.u16[GAMBIT_BASE + STOCK_OFF + 2 * i]) end
  return table.concat(w)
end

local function readBuild()
  local out = {}
  for m = 0, GAMBIT_MEMBERS - 1 do
    local r, bits = GAMBIT_BASE + 8 + m * BUILD_RECORD, {}
    for i = 0, BUILD_LIC_BYTES - 1 do bits[#bits + 1] = string.format("%02x", memory.u8[r + BUILD_LIC_OFF + i]) end
    out[#out + 1] = string.format("%02x%02x:%d:", memory.u8[r + 0x1C3], memory.u8[r + 0x1C4], memory.u32[r + 0x190])
                    .. table.concat(bits)
  end
  return table.concat(out, ",")
end

--Live licence-node table as {count, stride, hex}; nil, why when it is not available.
--A battle-pack table as {count, stride, hex} through its descriptor (u16 count +4, u16 stride +8, u32 data +0xC, plus the
--pack base); nil, why when it is not available.
local function readPackTable(descPtr, knownKey)
  local desc, base = memory.u64[descPtr], memory.u64[PACK_BASE_PTR]
  if desc == nil or desc == 0 then return nil, "desc_null" end
  if base == nil then return nil, "base_unreadable" end
  if desc < 0x10000 or desc > 0x7FFFFFFFFFFF then return nil, "desc_range" end
  local count, stride, off = memory.u16[desc + 4], memory.u16[desc + 8], memory.u32[desc + 0xC]
  if count < 1 or count > 1024 or stride < 8 or stride > 64 then return nil, "shape" end
  local first = (base + off) & 0xFFFFFFFF
  if first < 0x10000 then return nil, "records_null" end
  local total = count * stride
  local function hx(a, b) local h = {}; for i = a, b - 1 do h[#h + 1] = string.format("%02x", memory.u8[(first + i) & 0xFFFFFFFF]) end; return table.concat(h) end
  local key = count .. ":" .. stride .. ":" .. (2 * total) .. ":" .. hx(0, math.min(48, total)) .. hx(math.max(0, total - 48), total)
  if key == knownKey then return { key = key, same = true } end
  local t = {}
  for i = 0, count * stride - 1 do t[#t + 1] = string.format("%02x", memory.u8[(first + i) & 0xFFFFFFFF]) end
  local records = table.concat(t)
  key = count .. ":" .. stride .. ":" .. #records .. ":" .. records:sub(1, 96) .. records:sub(-96)
  return { count = count, stride = stride, records = records, key = key }
end

local function readLicNodes(k) return readPackTable(LIC_DESC_PTR, k) end

--0.5.12: same as licNodesTick for the equipment table (557 x 0x34 in this pack).
local function equipTick(now)
  local ok, n, why = pcall(readPackTable, EQUIP_DESC_PTR, equipKey)
  if not ok then equipInfo = { status = "read_failed" }; return end
  if not n then equipInfo = { status = why }; return end
  local key = n.key
  if key == equipKey then return end
  local f = io.open(EQUIP_FILE, "w")
  if not f then equipInfo = { status = "write_failed" }; return end
  f:write(encode({ contract = "ffxii.equipment.v1", count = n.count, stride = n.stride, records = n.records,
                   observedClockUs = now }))
  f:close()
  equipKey = key
  equipInfo = { status = "ok", count = n.count, stride = n.stride, file = "equipment.json", observedClockUs = now }
end

local function licNodesTick(now)
  local ok, n, why = pcall(readLicNodes, licNodesKey)
  if not ok then licNodesInfo = { status = "read_failed" }; return end
  if not n then licNodesInfo = { status = why }; return end
  local key = n.key
  if key == licNodesKey then return end
  local f = io.open(LICNODES_FILE, "w")
  if not f then licNodesInfo = { status = "write_failed" }; return end
  f:write(encode({ contract = "ffxii.licnodes.v1", count = n.count, stride = n.stride, records = n.records,
                   observedClockUs = now }))
  f:close()
  licNodesKey = key
  licNodesInfo = { status = "ok", count = n.count, stride = n.stride, file = "licence_nodes.json", observedClockUs = now }
end

--Status values: ok, unsupported_build, save_not_ready, read_failed, unstable.
local function buildState()
  local now = safeCall(event.getClock) or 0
  if build and not forceTable.due(buildClock, now, 4, 0, (GAMBITS_MS + 1500) * 1000) then
    build.licenceNodes, build.equipment = licNodesInfo, equipInfo   --latest side results
    return build
  end
  buildClock = now
  local r = { contract = "ffxii.build.v1" }
  if not supportedBuild() then r.status = "unsupported_build"
  else
    local okp, ptr = pcall(function() return memory.u64[GAMBIT_SAVE_PTR] end)
    if not okp then r.status = "read_failed"
    elseif ptr ~= GAMBIT_BASE or not saveLoaded() then r.status = "save_not_ready"
    else
      local ok1, a = pcall(readBuild)
      local ok2, b = pcall(readBuild)
      if not ok1 or not ok2 then r.status = "read_failed"
      elseif a ~= b then r.status = "unstable"
      else r.status = "ok"; r.members = a; r.stable = true; r.observedClockUs = now end
      local okA, oa, whyA = pcall(readOwned)
      local okB, ob = pcall(readOwned)
      if not okA or not okB then r.owned = { status = "read_failed" }
      elseif not oa then r.owned = { status = whyA }
      elseif not ob or encode(oa) ~= encode(ob) then r.owned = { status = "unstable" }
      else r.owned = { status = "ok", gambits = oa.gambits, magicks = oa.magicks, technicks = oa.technicks } end
      local okS1, s1 = pcall(readStock)
      local okS2, s2 = pcall(readStock)
      if not okS1 or not okS2 then r.stock = { status = "read_failed" }
      elseif s1 ~= s2 then r.stock = { status = "unstable" }
      else r.stock = { status = "ok", words = s1 } end
      local okG1, g1 = pcall(readGear)
      local okG2, g2 = pcall(readGear)
      if not okG1 or not okG2 then r.gear = { status = "read_failed" }
      elseif g1 ~= g2 then r.gear = { status = "unstable" }
      else r.gear = { status = "ok", members = g1 } end
      local okE1, e1 = pcall(readEquipStock)
      local okE2, e2 = pcall(readEquipStock)
      if not okE1 or not okE2 then r.equipStock = { status = "read_failed" }
      elseif e1 ~= e2 then r.equipStock = { status = "unstable" }
      else r.equipStock = { status = "ok", words = e1 } end
      local okK1, k1 = pcall(readKeyItems)
      local okK2, k2 = pcall(readKeyItems)
      if not okK1 or not okK2 then r.keyItems = { status = "read_failed" }
      elseif k1 ~= k2 then r.keyItems = { status = "unstable" }
      else r.keyItems = { status = "ok", words = k1 } end
      local okR1, r1 = pcall(readRoster)
      local okR2, r2 = pcall(readRoster)
      if not okR1 or not okR2 then r.roster = { status = "read_failed" }
      elseif r1 ~= r2 then r.roster = { status = "unstable" }
      else r.roster = { status = "ok", members = r1 } end
      licNodesTick(now)
      equipTick(now)
    end
  end
  r.licenceNodes = licNodesInfo
  r.equipment = equipInfo
  build = r
  return r
end

--Battle-state candidate (0.5.7, Members_Battle_Trace_2026-10-21.md), read-only static globals: u8 BATTLE_FLAGS is the
--party weapon-readiness state (0x04 = demand now, 0x02 = last update's demand, 0x01 = demand or auto-sheathe countdown
--BATTLE_COUNT running). Not proven to mean "in a fight"; this probe exists to check that live. Sampled every BATTLE_MS on
--its own timer; the last BATTLE_KEEP changes of the flag byte are kept in the snapshot.
local BATTLE_FLAGS = 0x22C1F48
local BATTLE_COUNT = 0x22C1F44
local BATTLE_MS = 100
local BATTLE_KEEP = 20
local battle = { contract = "ffxii.battle.v1", status = "not_started", changes = 0, transitions = {} }

local function battleTick()
  local now = safeCall(event.getClock) or 0
  if not supportedBuild() then battle.status = "unsupported_build"; return end
  local ok, f1, c, f2 = pcall(function() return memory.u8[BATTLE_FLAGS], memory.u32[BATTLE_COUNT], memory.u8[BATTLE_FLAGS] end)
  if not ok then battle.status = "read_failed"; return end
  if f1 ~= f2 then battle.status = "unstable"; return end
  battle.status = "ok"
  battle.observedClockUs = now
  battle.countdown = c
  if battle.flags == f1 then return end
  local t = { flags = f1, countdown = c, clockUs = now, wallTime = os.time(), mapId = safeCall(call and call.getCurrentMap) }
  if battle.flags ~= nil then   --the first sample is a baseline, not a change
    battle.changes = battle.changes + 1
    table.insert(battle.transitions, t)
    if #battle.transitions > BATTLE_KEEP then table.remove(battle.transitions, 1) end
  end
  t.first = battle.flags == nil or nil
  battle.flags = f1
  battle.demand = (f1 & 0x04) ~= 0
  battle.ready = (f1 & 0x01) ~= 0
  battle.candidate = (f1 & 0x05) ~= 0
end

--Field party (0.5.9, Party_Vitals_Trace_2026-10-21.md, ESTABLISHED statically; 0.5.10: verified on the Thor against
--the game's party menu, capability "on"). Operational slots: u16 at P + 0x5A5A + 2*slot, slots 0..3, low byte = member ID,
--0xFF = empty. Member record M = P + 8 + id*0x1C8 (actor+0x698 points here, so it is the live record): HP s32 +0x48,
--cached max s32 +0x24; MP s16 +0x4C, cached max s16 +0x28. Effective max as the game computes it (0x31FC10, 0x329220):
--flags F = u32 +0x3C | u32 +0x64; F bit16 -> max = max(cur, 1); else bit28 -> 2 x cached; MP max = 0 if u8 +0x6C or
--+0x7C has bit 0x80. Read every poll, twice; published only when both reads agree. Shape: PROPOSAL_party_family.md.
local PARTY_SLOTS_OFF, PARTY_SLOTS = 0x5A5A, 4
local PARTY_NAMES = { [0] = "Vaan", "Ashe", "Fran", "Balthier", "Basch", "Penelo" }   --Members_Battle_Trace (ESTABLISHED)

local function s32(a) local v = memory.u32[a]; return v >= 0x80000000 and v - 0x100000000 or v end
local function s16(a) local v = memory.u16[a]; return v >= 0x8000 and v - 0x10000 or v end

local function readParty()
  local out = {}
  for slot = 0, PARTY_SLOTS - 1 do
    local id = memory.u16[GAMBIT_BASE + PARTY_SLOTS_OFF + 2 * slot] & 0xFF
    if id ~= 0xFF and id <= 0x27 then
      local m = GAMBIT_BASE + 8 + id * BUILD_RECORD
      local hp, hpMax, mp, mpMax = s32(m + 0x48), s32(m + 0x24), s16(m + 0x4C), s16(m + 0x28)
      local f = memory.u32[m + 0x3C] | memory.u32[m + 0x64]
      if f & 0x00010000 ~= 0 then hpMax = math.max(hp, 1) elseif f & 0x10000000 ~= 0 then hpMax = 2 * hpMax end
      if (memory.u8[m + 0x6C] & 0x80) ~= 0 or (memory.u8[m + 0x7C] & 0x80) ~= 0 then mpMax = 0 end
      out[#out + 1] = { slot = slot, member = id, name = PARTY_NAMES[id], role = "active", status = f,
                        hp = { cur = hp, max = hpMax }, mp = { cur = mp, max = mpMax } }
    end
  end
  return out
end

--Status values: ok, unsupported_build, save_not_ready, read_failed, unstable.
local function partyState()
  local r = { contract = "ffxii.party.v1" }
  if not supportedBuild() then r.status = "unsupported_build"; return r end
  local okp, ptr = pcall(function() return memory.u64[GAMBIT_SAVE_PTR] end)
  if not okp then r.status = "read_failed"; return r end
  if ptr ~= GAMBIT_BASE or not saveLoaded() then r.status = "save_not_ready"; return r end
  local ok1, a = pcall(readParty)
  local ok2, b = pcall(readParty)
  if not ok1 or not ok2 then r.status = "read_failed"; return r end
  if encode(a) ~= encode(b) then r.status = "unstable"; return r end
  r.status = "ok"
  r.members = a
  return r
end

--0.13.0: what each field member is about to act on (read-only). Party_Target_Export_2026-10-22.md part B, checked
--against the exe on 22 Oct: leader u8 P+0x5AA4 (0x3271D0); actor pool at qword [0x208E688], count s32 [0x208E6A0],
--stride 0xF50 (0x236820); a member's actor has qword actor+0x698 == its record M (0x327150). u32 actor+0x710 is the
--pending action's target handle and u16 actor+0x714 its action (0x30BB30). A handle is never an address: it is
--resolved like 0x3588B0 (class (h>>16)&15 < 5, index h&0xFFFF, generation (h>>20)&0x7FF; manager 0x2098E10 +
--class*0x288 with qword+0 and list qword+8 non-null, byte+0x10 bit0, index below s32+0x20 and s32[list]; wrapper =
--qword list+8+8*index with u16 wrapper+0x16 == generation; actor = qword wrapper+0x30), and 0x31B7E0 also requires
--u8 [0x2089340] & 0x10. The target's stat record D = qword T+0x698: HP s32 +0x48, max s32 +0x24 (effective max as for
--the party), level u8 +0x1C2, status u32 +0x3C | +0x64, u8 +5 == 1 for an enemy. An enemy's live affinities (0x2FF2F9,
--gear and effects included): weak +0x40, absorb +0x41, half +0x42, immune +0x43, potency +0x44, bits fire,
--lightning, ice, earth, water, wind, holy, dark. Bestiary entry u16 [qword T+0xE68] + 0x52 (0x31B9A0), 0xFFFF none.
--This is the action's target, not proven to be the on-screen cursor. Read twice; published only when both agree.
--(Its own scope: the main chunk is at Lua's 200-local limit.)
local targetState = (function()
local ACTOR_POOL_PTR, ACTOR_COUNT, ACTOR_STRIDE, ACTOR_MAX = 0x208E688, 0x208E6A0, 0xF50, 1024
local HANDLE_MGR, HANDLE_MGR_STRIDE, TARGET_GATE, LEADER_OFF = 0x2098E10, 0x288, 0x2089340, 0x5AA4

local function nonNull(a) local v = memory.u64[a]; return v ~= 0 and v or nil end

--0.13.0 (performance): every read is a call into the loader, and on the Thor each runs under x86 emulation inside the
--game's frame. Scanning the whole actor pool every poll (up to ACTOR_MAX x 2) made frames uneven. Each member's slot
--in the pool is remembered and only re-checked (one read per member); the pool is scanned again only when a member
--is missing or moved, at most every RESCAN_MS.
local RESCAN_MS = 5000
local cache = { base = nil, n = nil, slot = {} }   -- member id -> pool index
local lastScanClock

local function memberActors(byRecord)
  local base, n = nonNull(ACTOR_POOL_PTR), s32(ACTOR_COUNT)
  local actors = {}
  if not base or n <= 0 or n > ACTOR_MAX then return actors end
  if base ~= cache.base or n ~= cache.n then cache.base, cache.n, cache.slot = base, n, {} end
  local missing = false
  for record, id in pairs(byRecord) do
    local i = cache.slot[id]
    if i and i < n and memory.u64[base + i * ACTOR_STRIDE + 0x698] == record then
      actors[id] = base + i * ACTOR_STRIDE
    else
      missing = true
    end
  end
  local now = safeCall(event.getClock) or 0
  if missing and (not lastScanClock or now - lastScanClock >= RESCAN_MS * 1000) then
    lastScanClock = now
    for i = 0, n - 1 do
      local a = base + i * ACTOR_STRIDE
      local id = byRecord[memory.u64[a + 0x698]]
      if id and not actors[id] then actors[id] = a; cache.slot[id] = i end
    end
  end
  return actors
end

local function resolveHandle(h)
  if h == 0 then return nil end
  local class, index, gen = (h >> 16) & 0xF, h & 0xFFFF, (h >> 20) & 0x7FF
  if class >= 5 then return nil end
  local mgr = HANDLE_MGR + class * HANDLE_MGR_STRIDE
  local list = nonNull(mgr + 8)
  if not list or memory.u64[mgr] == 0 or (memory.u8[mgr + 0x10] & 1) == 0 then return nil end
  if index >= s32(mgr + 0x20) or index >= s32(list) then return nil end
  local wrapper = nonNull(list + 8 + 8 * index)
  if not wrapper or memory.u16[wrapper + 0x16] ~= gen then return nil end
  return nonNull(wrapper + 0x30)
end

local function effectiveMax(d)
  local hp, max = s32(d + 0x48), s32(d + 0x24)
  local f = memory.u32[d + 0x3C] | memory.u32[d + 0x64]
  if f & 0x00010000 ~= 0 then max = math.max(hp, 1) elseif f & 0x10000000 ~= 0 then max = 2 * max end
  return hp, max, f
end

local function describeTarget(t, d)
  local hp, max, f = effectiveMax(d)
  local r = { hp = { cur = hp, max = max }, level = memory.u8[d + 0x1C2], status = f }
  local m = d - (GAMBIT_BASE + 8)
  if memory.u8[d + 5] == 1 then
    r.kind = "enemy"
    r.weak, r.absorb, r.half = memory.u8[d + 0x40], memory.u8[d + 0x41], memory.u8[d + 0x42]
    r.immune, r.potency = memory.u8[d + 0x43], memory.u8[d + 0x44]
    local sec = nonNull(t + 0xE68)
    local entry = sec and memory.u16[sec + 0x52]
    if entry and entry ~= 0xFFFF then r.bestiary = entry end
  elseif m >= 0 and m % BUILD_RECORD == 0 and m // BUILD_RECORD <= 0x27 then
    r.kind = "member"
    r.member = m // BUILD_RECORD
  else
    r.kind = "other"
  end
  return r
end

local function readTargets()
  local byRecord, members = {}, {}
  for slot = 0, PARTY_SLOTS - 1 do
    local id = memory.u16[GAMBIT_BASE + PARTY_SLOTS_OFF + 2 * slot] & 0xFF
    if id ~= 0xFF and id <= 0x27 then
      byRecord[GAMBIT_BASE + 8 + id * BUILD_RECORD] = id
      members[#members + 1] = id
    end
  end
  local actors = memberActors(byRecord)
  local gate = (memory.u8[TARGET_GATE] & 0x10) ~= 0
  local leader = memory.u8[GAMBIT_BASE + LEADER_OFF]
  local out = {}
  for _, id in ipairs(members) do
    local e = { member = id, leader = (id == leader) or nil }
    local a = actors[id]
    if a then
      local h = memory.u32[a + 0x710]
      if h ~= 0 then
        e.handle, e.action = h, memory.u16[a + 0x714]
        local t = gate and resolveHandle(h)
        local d = t and nonNull(t + 0x698)
        if d then e.target = describeTarget(t, d) end
      end
    end
    out[#out + 1] = e
  end
  return out
end

--Status values: ok, unsupported_build, save_not_ready, read_failed, unstable.
return function()
  local r = { contract = "ffxii.target.v1" }
  if not supportedBuild() then r.status = "unsupported_build"; return r end
  local okp, ptr = pcall(function() return memory.u64[GAMBIT_SAVE_PTR] end)
  if not okp then r.status = "read_failed"; return r end
  if ptr ~= GAMBIT_BASE or not saveLoaded() then r.status = "save_not_ready"; return r end
  local ok1, a = pcall(readTargets)
  local ok2, b = pcall(readTargets)
  if not ok1 or not ok2 then r.status = "read_failed"; return r end
  if encode(a) ~= encode(b) then r.status = "unstable"; return r end
  r.status = "ok"
  r.members = a
  return r
end
end)()

--0.13.0: the game's story flags, for the map (read-only; Map_Icons_Flags_2026-10-22.md, getters checked in the exe
--on 22 Oct). 64 bytes of generic flags at save 0x2164280 + 0x16F4 (0x32A8A0: ids 2..511 are bit id&7 of byte id/8);
--scenario variable 0 = u16 [qword 0x2098E58] when u8 [0x2098E20] & 1 (0x265270; the input of the bit14 threshold
--gates, 0 otherwise); the current exit = s16 [qword 0x2098E10] + 0x7E under the same gate (0x264770, -1 otherwise).
--The app uses them to dim locked exits and to show map symbols exactly when the game does. Read twice per snapshot.
local flagsState = (function()
local FLAGS_AT, FLAGS_BYTES, SCRIPT_GATE, SCENARIO_PTR, EXIT_PTR = 0x2165974, 64, 0x2098E20, 0x2098E58, 0x2098E10

local function readFlags()
  local raw = {}                                   --16 reads of 4 bytes, not 64 of 1 (see RESCAN_MS above); u32
  for i = 0, FLAGS_BYTES - 4, 4 do raw[#raw + 1] = string.pack("<I4", memory.u32[FLAGS_AT + i]) end   --stays exact
  local generic = table.concat(raw):gsub(".", function(c) return string.format("%02x", c:byte()) end)
  local r = { generic = generic, scenario = 0, currentExit = -1 }
  if (memory.u8[SCRIPT_GATE] & 1) ~= 0 then
    local s = memory.u64[SCENARIO_PTR]
    if s ~= 0 then r.scenario = memory.u16[s] end
    local e = memory.u64[EXIT_PTR]
    if e ~= 0 then r.currentExit = s16(e + 0x7E) end
  end
  return r
end

--Status values: ok, unsupported_build, save_not_ready, read_failed, unstable.
return function()
  local r = { contract = "ffxii.flags.v1" }
  if not supportedBuild() then r.status = "unsupported_build"; return r end
  local okp, ptr = pcall(function() return memory.u64[GAMBIT_SAVE_PTR] end)
  if not okp then r.status = "read_failed"; return r end
  if ptr ~= GAMBIT_BASE or not saveLoaded() then r.status = "save_not_ready"; return r end
  local ok1, a = pcall(readFlags)
  local ok2, b = pcall(readFlags)
  if not ok1 or not ok2 then r.status = "read_failed"; return r end
  if encode(a) ~= encode(b) then r.status = "unstable"; return r end
  r.status = "ok"
  r.generic, r.scenario, r.currentExit = a.generic, a.scenario, a.currentExit
  return r
end
end)()

--0.14.0: the hunt journal's live state (read-only; Objectives_Hunts_2026-10-22.md, checked in the exe on 23 Oct). Two
--32-byte banks the game's quest menu tests, bit (id & 7) of byte id / 8 in either one lists a quest (0x46B240 at save
--+0x1200, 0x46B1A0 at save +0x1220; 0x37EAA0), and the stage byte of quests 128..172 (the 45 hunts) at save +0x1264 +
--id (0x46B180). The app turns a stage into the hunt's phase with the table built from the user's files. 28 u32 reads.
local questsState = (function()
local BANK_A, BANK_B, STAGES, FIRST, COUNT = 0x2165480, 0x21654A0, 0x2165564, 128, 45

local function hex(at, bytes)
  local raw = {}
  for i = 0, bytes - 1, 4 do raw[#raw + 1] = string.pack("<I4", memory.u32[at + i]) end
  return table.concat(raw):sub(1, bytes):gsub(".", function(c) return string.format("%02x", c:byte()) end)
end

local function readQuests()
  return { bankA = hex(BANK_A, 32), bankB = hex(BANK_B, 32), stages = hex(STAGES, COUNT) }
end

local last, lastClock                             --hunt state changes rarely: read every GAMBITS_MS, not every poll

--Status values: ok, unsupported_build, save_not_ready, read_failed, unstable.
return function()
  local now = safeCall(event.getClock) or 0
  if last and not forceTable.due(lastClock, now, 4, 3, (GAMBITS_MS + 1500) * 1000) then return last end
  lastClock = now
  last = { contract = "ffxii.quests.v1" }
  local r = last
  if not supportedBuild() then r.status = "unsupported_build"; return r end
  local okp, ptr = pcall(function() return memory.u64[GAMBIT_SAVE_PTR] end)
  if not okp then r.status = "read_failed"; return r end
  if ptr ~= GAMBIT_BASE or not saveLoaded() then r.status = "save_not_ready"; return r end
  local ok1, a = pcall(readQuests)
  local ok2, b = pcall(readQuests)
  if not ok1 or not ok2 then r.status = "read_failed"; return r end
  if encode(a) ~= encode(b) then r.status = "unstable"; return r end
  r.status = "ok"
  r.first, r.count, r.bankA, r.bankB, r.stages = FIRST, COUNT, a.bankA, a.bankB, a.stages
  return r
end
end)()

--Raw copy (0.15.0, Broker_Plan_2026-10-23.md): so the app can build the data pack itself, with no PC step. Off until
--the app drops a request file. Copies the game files it lists, unchanged, to <BASE_DIR>raw/<job>/, 4 KiB per 50 ms
--tick; the app does all parsing, inflating and checking. Read-only on game files (opened "rb"); writes only under raw/.
--Never reads game memory. Pauses in fights (battle.candidate) and while the app's heartbeat (raw/alive.txt, the app's
--os time) is older than ALIVE_S. Every entry is re-checked against the archive's own directory before copying; any
--mismatch or I/O error stops the job with a reason until the app writes a new request (new job id).
--Request (<= 40 KiB, LF lines): contract=ffxii.rawcopy.v1 / job=<id> / then up to MAX_ITEMS of
--  item=header                                      the archive's SRYK header (its own length field, <= 64 MiB)
--  item=entry <ordinal> <storedBytes> <name>        one directory entry's stored (compressed) blocks
--  item=config <n>                                  one of CONFIGS[n] (x64-relative), whole file, <= 4 MiB
--Output: raw/<job>/NNN.bin per item (NNN = item number), raw/<job>/receipts.txt ("NNN bytes" per finished item),
--raw/<job>/cursor.txt (resume point), raw/<job>/done.txt or raw/<job>/error.txt. Progress in data.rawcopy.
local rawCopy = (function()
local RAW_DIR = BASE_DIR .. "raw/"
local REQUEST = RAW_DIR .. "request.txt"
local REQUEST_ERROR = RAW_DIR .. "request_error.txt"
local ALIVE = RAW_DIR .. "alive.txt"
local CONTRACT = "ffxii.rawcopy.v1"
local SOURCES = { "A:/FFXII_TZA.vbf", "../FFXII_TZA.vbf" }          --GameNative maps the game root to A:; x64 is cwd
local CONFIGS = {
  "scripts/config/TheInsurgentsDescriptiveInventoryConfig/us.lua",
  "scripts/config/FoedexClanPrimerTextReplacerConfig/Bestiary/us.lua",
  "scripts/config/FoedexClanPrimerTextReplacerConfig/TravelersTips/us.lua",
}
local TICK_MS, SLICE, CURSOR_EVERY = 50, 4096, 16
local MAX_REQUEST, MAX_ITEMS, MAX_HEADER, MAX_ENTRY, MAX_CONFIG = 40 << 10, 128, 64 << 20, 64 << 20, 4 << 20
local CHECK_EVERY_S, ALIVE_S = 2, 30

local st = { contract = CONTRACT, status = "idle" }   --published as data.rawcopy
local job                                             --{ id, items, dir }
local cur                                             --current item: { n, src, dst, size, off, from }
local running, stopped, lastCheck = false, false, -CHECK_EVERY_S * 1000000
local pendingMarker, lastRefusal
local MARKER_TRIES = 20                               --one immediate attempt, then at most 19 x 50 ms
local lastAlive, aliveChangedAt, nextAliveCheck, away = nil, nil, 0, true
local function clockUs() return event.getClock() end  --loader monotonic microseconds, not os.time()

local function readSmall(path, cap)
  local f = io.open(path, "rb")
  if not f then return nil end
  local s = f:read(cap + 1)
  f:close()
  if not s or #s > cap then return nil end
  return s
end

local function writeSmall(path, text)
  local f = io.open(path, "wb")
  if not f then return false end
  local ok = f:write(text)
  local closed = f:close()
  return ok ~= nil and closed ~= nil
end

--Publish only a complete marker; never turn a short write into an apparent success.
local function queueMarker(path, text)
  local p = { path = path, text = text, attempts = 0 }
  pendingMarker = p
  st.markerError, st.markerAttempts, st.markerPending = nil, 0, true
  local function attempt()
    if stopped or pendingMarker ~= p then return end
    p.attempts = p.attempts + 1
    local ok, wrote = pcall(function()
      return writeSmall(p.path .. ".tmp", p.text) and os.rename(p.path .. ".tmp", p.path)
    end)
    st.markerAttempts = p.attempts
    if ok and wrote then
      pendingMarker = nil
      st.markerError, st.markerPending = nil, false
    else
      st.markerError = "marker_write"                 --terminal status/error remain backward compatible
      if p.attempts < MARKER_TRIES then event.executeAfterMs(TICK_MS, attempt)
      else pendingMarker = nil; st.markerPending = false end
    end
  end
  attempt()
end

local function closeCur()
  if cur then
    if cur.src then pcall(cur.src.close, cur.src) end
    if cur.dst then pcall(cur.dst.close, cur.dst) end
  end
  cur = nil
end

local function fail(reason)
  closeCur()
  st.status, st.error = "error", reason
  running = false
  if job then queueMarker(job.dir .. "error.txt", reason .. "\n") end
end

local function openArchive()
  for _, p in ipairs(SOURCES) do
    local f = io.open(p, "rb")
    if f then return f end
  end
  return nil
end

local function u32(s, i) return string.unpack("<I4", s, i) end
local function u64(s, i) return string.unpack("<I8", s, i) end

--Directory row + block codes for one entry, read from the archive itself. Returns stored offset, stored length, name.
local function locate(f, ordinal)
  f:seek("set", 0)
  local pre = f:read(16)
  if not pre or #pre ~= 16 or pre:sub(1, 4) ~= "SRYK" then return nil, "not_sryk" end
  local hlen, n = u32(pre, 5), u64(pre, 9)
  if n <= 0 or n > 500000 or hlen > MAX_HEADER or ordinal < 0 or ordinal >= n then return nil, "directory" end
  f:seek("set", 16 + 16 * n + 32 * ordinal)
  local row = f:read(32)
  if not row or #row ~= 32 then return nil, "directory" end
  local first, size, off, nameOff = u32(row, 1), u64(row, 9), u64(row, 17), u64(row, 25)
  f:seek("set", 16 + 48 * n)
  local nl = f:read(4)
  if not nl or #nl ~= 4 then return nil, "directory" end
  local namesLen = u32(nl, 1)
  if namesLen < 4 or nameOff >= namesLen - 4 or size <= 0 or size > MAX_ENTRY then return nil, "directory" end
  f:seek("set", 16 + 48 * n + 4 + nameOff)
  local raw = f:read(math.min(1024, namesLen - 4 - nameOff)) or ""
  local name = raw:match("^([^%z]*)%z")
  if not name then return nil, "name" end
  local blocks = (size + 65535) // 65536
  local codesAt = 16 + 48 * n + namesLen + 2 * first
  if codesAt + 2 * blocks > hlen then return nil, "blocks" end
  f:seek("set", codesAt)
  local codes = f:read(2 * blocks)
  if not codes or #codes ~= 2 * blocks then return nil, "blocks" end
  local stored = 0
  for i = 1, 2 * blocks, 2 do
    local c = string.unpack("<I2", codes, i)
    stored = stored + (c == 0 and 65536 or c)
  end
  return off, stored, name
end

local function parseRequest(text)
  if not text or #text > MAX_REQUEST then return nil, "request_size" end
  local id, items, contract = nil, {}, nil
  for line in text:gmatch("[^\n]+") do
    local k, v = line:match("^(%a+)=(.*)$")
    if k == "contract" and not contract then contract = v
    elseif k == "job" and not id then
      if not v:match("^[%w_%-]+$") or #v > 32 then return nil, "job_id" end
      id = v
    elseif k == "item" then
      if #items >= MAX_ITEMS then return nil, "too_many_items" end
      if v == "header" then items[#items + 1] = { kind = "header" }
      else
        local o, b, nm = v:match("^entry (%d+) (%d+) ([%w_/%.%-]+)$")
        local c = v:match("^config (%d)$")
        if o and #o <= 7 and #b <= 9 and #nm <= 255 and not nm:find("..", 1, true) then   --a name, never a path
          items[#items + 1] = { kind = "entry", ordinal = tonumber(o), bytes = tonumber(b), name = nm }
        elseif c and CONFIGS[tonumber(c)] then items[#items + 1] = { kind = "config", path = CONFIGS[tonumber(c)] }
        else return nil, "item" end
      end
    else return nil, "line" end
  end
  if contract ~= CONTRACT or not id or #items == 0 then return nil, "request" end
  return { id = id, items = items, dir = RAW_DIR .. id .. "/" }
end

local function itemFile(n) return string.format("%03d.bin", n) end

--Opens item n for copying from byte `from` (resume) or 0.
local function openItem(n, from)
  local it = job.items[n]
  local src, off, size
  if it.kind == "config" then
    src = io.open(it.path, "rb")
    if not src then return fail("config_missing") end
    size = src:seek("end")
    if not size or size > MAX_CONFIG then src:close(); return fail("config_size") end
    off = 0
  else
    src = openArchive()
    if not src then return fail("archive_missing") end
    if it.kind == "header" then
      src:seek("set", 0)
      local pre = src:read(16)
      if not pre or #pre ~= 16 or pre:sub(1, 4) ~= "SRYK" then src:close(); return fail("not_sryk") end
      size, off = u32(pre, 5), 0
      if size > MAX_HEADER then src:close(); return fail("header_size") end
    else
      local o, stored, name = locate(src, it.ordinal)
      if not o then src:close(); return fail("entry_" .. tostring(stored)) end
      if stored ~= it.bytes or name ~= it.name then src:close(); return fail("entry_mismatch") end
      off, size = o, stored
    end
  end
  from = (from and from >= 0 and from <= size) and from or 0
  local path = job.dir .. itemFile(n) .. ".part"
  local dst = from > 0 and io.open(path, "r+b") or nil
  if dst then dst:seek("set", from) else from = 0; dst = io.open(path, "wb") end
  if not dst then src:close(); return fail("write_open") end
  if not src:seek("set", off + from) then src:close(); dst:close(); return fail("seek") end
  cur = { n = n, src = src, dst = dst, size = size, off = off, from = from, slices = 0 }
  st.item, st.items, st.bytes, st.total = n, #job.items, from, size
  return true
end

local function finishItem()
  local n, size = cur.n, cur.size
  cur.dst:flush()
  local got = cur.dst:seek("end")
  closeCur()
  if got ~= size then return fail("size_after_copy") end
  local part, final = job.dir .. itemFile(n) .. ".part", job.dir .. itemFile(n)
  os.remove(final)
  if not os.rename(part, final) then return fail("rename") end
  local r = io.open(job.dir .. "receipts.txt", "ab")
  if not r then return fail("receipt") end
  r:write(string.format("%03d %d\n", n, size))
  r:close()
  writeSmall(job.dir .. "cursor.txt", string.format("%d 0\n", n + 1))
  if n == #job.items then
    st.status, st.error, running = "done", nil, false
    queueMarker(job.dir .. "done.txt", "done\n")
    return true
  end
  return openItem(n + 1, 0)
end

local function appAway(now)
  local alive = (readSmall(ALIVE, 32) or ""):match("^(%d+)%s*$")
  if alive and #alive <= 20 and alive ~= lastAlive then
    lastAlive, aliveChangedAt = alive, now
  end
  --First observation grants one lease, even for an old file; no foreign clock can extend it.
  return not aliveChangedAt or now - aliveChangedAt > ALIVE_S * 1000000
end
local function tick()
  if stopped or not running then return end
  local ok = pcall(function()
    local now = clockUs()
    if now >= nextAliveCheck then
      away, nextAliveCheck = appAway(now), now + CHECK_EVERY_S * 1000000
    end
    local pauseReason = battle.candidate and "fight" or (away and "app_away") or nil   --fight: checked every tick
    st.paused = pauseReason
    if pauseReason or not cur then return end
    local left = cur.size - cur.from
    local b = cur.src:read(math.min(SLICE, left))
    if not b or #b == 0 then return fail("read") end
    if not cur.dst:write(b) then return fail("write") end
    cur.from = cur.from + #b
    st.bytes = cur.from
    cur.slices = cur.slices + 1
    if cur.slices % CURSOR_EVERY == 0 then
      cur.dst:flush()
      writeSmall(job.dir .. "cursor.txt", string.format("%d %d\n", cur.n, cur.from))
    end
    if cur.from >= cur.size then finishItem() end
  end)
  if not ok then fail("tick") end
  if running and not stopped then event.executeAfterMs(TICK_MS, tick) end
end

--Called from the 500 ms poll: looks for a (new) request at most every CHECK_EVERY_S while idle.
local function poll()
  if running or stopped or pendingMarker then return end
  local now = clockUs()
  if now - lastCheck < CHECK_EVERY_S * 1000000 then return end
  away, nextAliveCheck = appAway(now), now + CHECK_EVERY_S * 1000000
  lastCheck = now
  local f = io.open(REQUEST, "rb")
  if not f then return end
  local text = f:read(MAX_REQUEST + 1) or ""
  f:close()
  local j, why = parseRequest(text)               --an oversized request is reported (request_size), not ignored
  if not j then
    st.status, st.error, st.job = "error", why, nil    --do not attribute a new refusal to the old job
    if text ~= lastRefusal then
      lastRefusal = text
      os.remove(REQUEST_ERROR)                       --Wine rename need not replace an existing file
      local id = text:match("^job=([^\n]+)") or text:match("\njob=([^\n]+)") or ""
      id = id:gsub("\r$", "")  --normalize a CRLF job line before safe refusal correlation
      if #id > 32 or not id:match("^[A-Za-z0-9_%-]+$") then id = "" end
      st.requestJob = id
      queueMarker(REQUEST_ERROR, "contract=" .. CONTRACT .. "\njob=" .. id .. "\nerror=" .. why .. "\n")
    end
    return
  end
  if lastRefusal then os.remove(REQUEST_ERROR); lastRefusal = nil end
  st.requestJob = nil
  if job and job.id == j.id and st.status ~= "running" then return end   --finished or failed: wait for a new job id
  job = j
  st.job, st.error, st.paused = j.id, nil, nil
  os.remove(REQUEST_ERROR)                           --also discard a refusal left by an earlier core session
  st.markerError, st.markerAttempts, st.markerPending = nil, nil, nil
  if lfs and lfs.mkdir then pcall(lfs.mkdir, RAW_DIR:sub(1, -2)); pcall(lfs.mkdir, j.dir:sub(1, -2)) end
  if readSmall(j.dir .. "done.txt", 16) then st.status = "done"; return end
  if readSmall(j.dir .. "error.txt", 256) then st.status, st.error = "error", "previous_error"; return end
  local n, from = (readSmall(j.dir .. "cursor.txt", 64) or ""):match("^(%d+) (%d+)")
  n, from = tonumber(n) or 1, tonumber(from) or 0
  if n < 1 or n > #j.items then n, from = 1, 0 end
  st.status, running = "running", true
  if openItem(n, from) then event.executeAfterMs(TICK_MS, tick) end
end

local function stop()
  stopped = true
  running, pendingMarker = false, nil
  closeCur()
end

return { poll = poll, stop = stop, status = function() return st end }
end)()

local function addReveal(data)
  if data.map then
    data.party = partyState()
    data.target = targetState()
    data.flags = flagsState()
    data.quests = questsState()
    --sampled just now, as this snapshot is built: stamp it with the snapshot's own clock (the receiver's freshness test
    --compares the two, and a later stamp would read as "time unknown")
    if data.party.status == "ok" then data.party.observedClockUs = data.session and data.session.clockUs end
    data.battle = battle
    data.reveal = revealState(data.map.mapId)
    data.position = positionState()
    data.gambits = gambitState()
    data.gambitEdit = editState()
    data.build = buildState()
    data.revealTable = revealTableState(forceTable.force)
    forceTable.force = false
  end
  return data
end

local function writeSnapshot(force)
  local data = addReveal(snapshot())
  data.rawcopy = rawCopy.status()
  --observedClockUs changes every read, so leave it out of the change test
  local function list(...)   --nil -> false, so a missing field cannot cut the list short (# stops at a nil hole)
    local t = table.pack(...)
    for i = 1, t.n do if t[i] == nil then t[i] = false end end
    t.n = nil
    return t
  end
  local digest = encode(list(data.map, data.reveal and data.reveal.status, data.reveal and data.reveal.record,
                          data.reveal and data.reveal.liveStatus,
                          data.reveal and data.reveal.live and encode(data.reveal.live),
                          data.revealTable and data.revealTable.status, data.revealTable and data.revealTable.records,
                          data.position and data.position.status, data.position and data.position.x,
                          data.position and data.position.z, data.position and data.position.originX,
                          data.gambits and data.gambits.status, data.gambits and data.gambits.sets,
                          data.gambits and data.gambits.active,
                          data.gambitEdit, data.build and data.build.status, data.build and data.build.members,
                          data.build and data.build.owned and encode(data.build.owned),
                          data.build and data.build.stock and encode(data.build.stock),
                          data.build and data.build.gear and encode(data.build.gear),
                          data.build and data.build.equipStock and encode(data.build.equipStock),
                          data.build and data.build.keyItems and encode(data.build.keyItems),
                          data.build and data.build.roster and encode(data.build.roster),
                          data.build and data.build.equipment and data.build.equipment.count,
                          data.build and data.build.licenceNodes and data.build.licenceNodes.count,
                          data.party and data.party.status, data.party and encode(data.party.members),
                          data.target and data.target.status, data.target and encode(data.target.members),
                          data.flags and data.flags.status, data.flags and data.flags.generic,
                          data.flags and data.flags.scenario, data.flags and data.flags.currentExit,
                          data.quests and data.quests.status, data.quests and data.quests.bankA,
                          data.quests and data.quests.bankB, data.quests and data.quests.stages,
                          data.battle and data.battle.status, data.battle and data.battle.flags,
                          data.battle and data.battle.changes,
                          data.rawcopy.status, data.rawcopy.item, data.rawcopy.bytes, data.rawcopy.paused,
                          data.rawcopy.error))
  local now = safeCall(event.getClock) or 0
  if not force and digest == lastDigest and (now - lastPublishClock) < HEARTBEAT_MS * 1000 then
    return
  end
  seq = seq + 1
  local text = encode({ v = PROTOCOL, type = "snapshot", seq = seq, data = data })
  --Two alternating files instead of rename: the reader takes the valid file with the highest seq,
  --so a half-written file is never the only copy.
  local f = io.open(SLOT_FILES[slot], "w")
  if f then
    f:write(text)
    f:close()
    slot = (slot == 1) and 2 or 1
    lastDigest = digest
    lastPublishClock = now
  end
end

--0.5.13: set by onExit. The timer chains below re-armed themselves forever, even during shutdown; the game sometimes
--never finished exiting (main thread spinning after the loader's "Cleaning up"). Once exiting, they stop.
local exiting = false

local function battlePoll()
  if exiting then return end
  pcall(battleTick)
  event.executeAfterMs(BATTLE_MS, battlePoll)
end

local function poll()
  if exiting then return end
  forceTable.poll = forceTable.poll + 1
  pcall(editTick)
  pcall(rawCopy.poll)
  pcall(writeSnapshot, false)  --a transient output failure must not stop future polls
  event.executeAfterMs(POLL_MS, poll)
end

local function onMapJump(locationId, posIndex, flags, unknown)
  if exiting then return end
  mapJumps = mapJumps + 1
  forceTable.force = true
  forceGambits = true
  lastJump = { locationId = locationId, posIndex = posIndex, flags = flags, unknown = unknown, clockUs = safeCall(event.getClock) }
  writeSnapshot(true)
end

local function onExit(kind)
  exiting = true
  pcall(rawCopy.stop)
  seq = seq + 1
  local f = io.open(SLOT_FILES[slot], "w")
  if f then
    f:write(encode({ v = PROTOCOL, type = "snapshot", seq = seq, data = { hello = staticHello, session = { exiting = true, exitType = kind, wallTime = os.time() } } }))
    f:close()
  end
end

local function start()
  ensureOutDir()
  revealTable, revealTableClock = nil, nil
  forceTable.force, forceTable.poll = true, 0
  gambits, gambitsClock, forceGambits = nil, nil, true
  staticHello = {
    protocol = PROTOCOL,
    producer = PRODUCER,
    transport = "file-ab",
    build = buildInfo(),
    loader = { lua = loaderVersion(), plugins = pluginInventory() },
    mods = modInventory(),
    capabilities = capabilities(),
  }
  pcall(battleTick)
  writeSnapshot(true)
  event.executeAfterMs(POLL_MS, poll)
  event.executeAfterMs(BATTLE_MS, battlePoll)
end

event.registerEventAsync("onMapJump", onMapJump)
event.registerEventAsync("exit", onExit)
event.registerEventAsync("onInitDone", start)
--Editor frame hook: at load only, permanent (no oneShot), and only when armed now; never (un)registered later.
if event.registerEventSync and fileExists(EDIT_ARM) then
  editHooked = event.registerEventSync("onFlip", function() if editPending then pcall(applyPendingEdit) end end) ~= false
end
