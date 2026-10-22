--ThorCompanion.lua: game-side script for the FFXII Thor Companion (https://github.com/Cybranfox/ffxii-thor-companion).
--Runs under ffgriever's FF12 LUA Script Loader. Reads the running game's state (location, map, party HP/MP, gambits,
--licences, gear, stock) and writes it as JSON to OUT_DIR for the companion app on the second screen.
--Read-only, except the opt-in gambit editor: it stays OFF unless <BASE_DIR>cmd/ARMED existed when the game started,
--and then only writes the one gambit slot a companion command names, after re-checking it. Never calls game functions,
--never touches saves, never opens a network connection. Only the supported game build is read (see supportedBuild).
--Protocol: docs/PROTOCOL.md. History: CHANGELOG.md.

local PROTOCOL = 1
local PRODUCER = { name = "ThorCompanion-lua", version = "0.9.0-beta" }

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

local function supportedBuild()
  local ok, res = pcall(function()
    if memory.u16[IMAGE_BASE] ~= 0x5A4D then return false end
    local lfanew = memory.u32[IMAGE_BASE + 0x3C]
    if memory.u32[IMAGE_BASE + lfanew] ~= 0x00004550 then return false end
    return memory.u32[IMAGE_BASE + lfanew + 0x08] == SUPPORTED_TIME_DATE_STAMP
  end)
  return ok and res == true
end

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
  if not force and revealTable and revealTableClock and now - revealTableClock < REVEAL_TABLE_MS * 1000 then
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
local function revealState(mapId)
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

--A mod's config file is a Lua chunk returning plain tables; it runs with an empty environment (no globals, no I/O).
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
      loaderVersion = loaderVersion(),
      clockUs = safeCall(event.getClock),
      wallTime = os.time(),
    },
    map = mapState(),
  }
end

local forceTable = true

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

--Status values: ok, unsupported_build, save_not_ready, read_failed, unstable.
local function gambitState()
  local now = safeCall(event.getClock) or 0
  if not forceGambits and gambits and gambitsClock and now - gambitsClock < GAMBITS_MS * 1000 then return gambits end
  forceGambits = false
  gambitsClock = now
  local r = { contract = "ffxii.gambits.v1" }
  if not supportedBuild() then r.status = "unsupported_build"
  else
    local okp, ptr = pcall(function() return memory.u64[GAMBIT_SAVE_PTR] end)
    if not okp then r.status = "read_failed"
    elseif ptr ~= GAMBIT_BASE then r.status = "save_not_ready"
    else
      local ok1, a = pcall(readGambitSets)
      local ok2, b = pcall(readGambitSets)
      if not ok1 or not ok2 then r.status = "read_failed"
      elseif a ~= b then r.status = "unstable"
      else r.status = "ok"; r.sets = a; r.stable = true; r.observedClockUs = now end
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

local function fileExists(path)
  local f = io.open(path, "rb")
  if not f then return false end
  f:close()
  return true
end

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
    for o = 0, 0x1F do b[o] = memory.u8[r + o] end
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

local function validAction(a)
  return a <= 0x50 or (a >= 0x52 and a <= 0x91) or a == 0x96 or (a >= 0x9E and a <= 0x9E + 0x17)
end

local function parseCommand(text)
  local lines = {}
  for line in text:gmatch("[^\r\n]+") do lines[#lines + 1] = line end
  if lines[1] ~= CMD_CONTRACT then return nil, "bad_contract" end
  local c = {}
  for i = 2, #lines do
    local k, v = lines[i]:match("^(%a+)=([%w%-]+)$")
    if not k then return nil, "bad_line" end
    c[k] = v
  end
  local function dec(s, lo, hi)
    local n = s and s:match("^%d+$") and tonumber(s)
    if n and n >= lo and n <= hi then return n end
  end
  local function hx(s) return s and s:match("^%x%x%x%x$") and tonumber(s, 16) or nil end
  local cmd = { id = c.id, op = c.op, issued = dec(c.issued, 0, 1 << 40), member = dec(c.member, 0, GAMBIT_MEMBERS - 1),
                set = dec(c.set, 0, 2), slot = dec(c.slot, 0, 11), cond = hx(c.cond), action = hx(c.action),
                bits = hx(c.bits), newCond = hx(c.newCond), newAction = hx(c.newAction) }
  if not (cmd.id and cmd.id:match("^[%w%-]+$") and #cmd.id >= 8 and #cmd.id <= 40) then return nil, "bad_id" end
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
  if memory.u64[GAMBIT_SAVE_PTR] ~= GAMBIT_BASE then return nil, "save_not_ready" end
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

--Runs on the game's frame thread, from the permanent sync onFlip hook (registered at load when armed).
local function applyPendingEdit()
  local p = editPending
  editPending = nil
  if not p then return end
  local now = safeCall(event.getClock) or 0
  if now > p.deadline then finishEdit(editResult(p.cmd, "expired", "apply_late")); return end
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
  local text = f:read("a") or ""
  f:close()
  if not os.remove(EDIT_CMD) then
    local r = editResult(nil, "rejected", "cannot_consume")
    if editLast == nil or editLast.reason ~= "cannot_consume" then finishEdit(r) end
    return
  end
  local cmd, why, partial = parseCommand(text)
  if not cmd then finishEdit(editResult(partial, "rejected", why)); return end
  if editSeen[cmd.id] then finishEdit(editResult(cmd, "rejected", "duplicate_id")); return end
  editSeen[cmd.id] = true
  if not fileExists(EDIT_ARM) then finishEdit(editResult(cmd, "rejected", "not_armed")); return end
  if not editHooked then finishEdit(editResult(cmd, "rejected", "editor_not_started")); return end
  local age = os.time() - cmd.issued
  if age > CMD_MAX_AGE_S or age < -30 then finishEdit(editResult(cmd, "rejected", "command_too_old")); return end
  if editPending then finishEdit(editResult(cmd, "rejected", "busy")); return end
  local ok, plan, reason, extra = pcall(planEdit, cmd)
  if not ok then finishEdit(editResult(cmd, "rejected", "check_failed")); return end
  if not plan then finishEdit(editResult(cmd, "rejected", reason, extra)); return end
  editPending = { cmd = cmd, deadline = now + APPLY_TIMEOUT_US }   --the load-time frame hook applies it (no registration here)
end

local tableClock = nil

local function editState()
  --condition-table diagnostics + copy refresh every 2 s (read-only)
  local now = safeCall(event.getClock) or 0
  if not tableClock or now - tableClock >= GAMBITS_MS * 1000 then
    tableClock = now
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
local function readPackTable(descPtr)
  local desc, base = memory.u64[descPtr], memory.u64[PACK_BASE_PTR]
  if desc == nil or desc == 0 then return nil, "desc_null" end
  if base == nil then return nil, "base_unreadable" end
  if desc < 0x10000 or desc > 0x7FFFFFFFFFFF then return nil, "desc_range" end
  local count, stride, off = memory.u16[desc + 4], memory.u16[desc + 8], memory.u32[desc + 0xC]
  if count < 1 or count > 1024 or stride < 8 or stride > 64 then return nil, "shape" end
  local first = (base + off) & 0xFFFFFFFF
  if first < 0x10000 then return nil, "records_null" end
  local t = {}
  for i = 0, count * stride - 1 do t[#t + 1] = string.format("%02x", memory.u8[(first + i) & 0xFFFFFFFF]) end
  return { count = count, stride = stride, records = table.concat(t) }
end

local function readLicNodes() return readPackTable(LIC_DESC_PTR) end

--0.5.12: same as licNodesTick for the equipment table (557 x 0x34 in this pack).
local function equipTick(now)
  local ok, n, why = pcall(readPackTable, EQUIP_DESC_PTR)
  if not ok then equipInfo = { status = "read_failed" }; return end
  if not n then equipInfo = { status = why }; return end
  local key = n.count .. ":" .. n.stride .. ":" .. #n.records .. ":" .. n.records:sub(1, 96) .. n.records:sub(-96)
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
  local ok, n, why = pcall(readLicNodes)
  if not ok then licNodesInfo = { status = "read_failed" }; return end
  if not n then licNodesInfo = { status = why }; return end
  local key = n.count .. ":" .. n.stride .. ":" .. #n.records .. ":" .. n.records:sub(1, 96) .. n.records:sub(-96)
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
  if build and buildClock and now - buildClock < GAMBITS_MS * 1000 then
    build.licenceNodes, build.equipment = licNodesInfo, equipInfo   --latest side results
    return build
  end
  buildClock = now
  local r = { contract = "ffxii.build.v1" }
  if not supportedBuild() then r.status = "unsupported_build"
  else
    local okp, ptr = pcall(function() return memory.u64[GAMBIT_SAVE_PTR] end)
    if not okp then r.status = "read_failed"
    elseif ptr ~= GAMBIT_BASE then r.status = "save_not_ready"
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
      out[#out + 1] = { slot = slot, member = id, name = PARTY_NAMES[id], role = "active",
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
  if ptr ~= GAMBIT_BASE then r.status = "save_not_ready"; return r end
  local ok1, a = pcall(readParty)
  local ok2, b = pcall(readParty)
  if not ok1 or not ok2 then r.status = "read_failed"; return r end
  if encode(a) ~= encode(b) then r.status = "unstable"; return r end
  r.status = "ok"
  r.members = a
  return r
end

local function addReveal(data)
  if data.map then
    data.party = partyState()
    --sampled just now, as this snapshot is built: stamp it with the snapshot's own clock (the receiver's freshness test
    --compares the two, and a later stamp would read as "time unknown")
    if data.party.status == "ok" then data.party.observedClockUs = data.session and data.session.clockUs end
    data.battle = battle
    data.reveal = revealState(data.map.mapId)
    data.position = positionState()
    data.gambits = gambitState()
    data.gambitEdit = editState()
    data.build = buildState()
    data.revealTable = revealTableState(forceTable)
    forceTable = false
  end
  return data
end

local function writeSnapshot(force)
  local data = addReveal(snapshot())
  --observedClockUs changes every read, so leave it out of the change test
  local function list(...)   --nil -> false, so a missing field cannot cut the list short (# stops at a nil hole)
    local t = table.pack(...)
    for i = 1, t.n do if t[i] == nil then t[i] = false end end
    t.n = nil
    return t
  end
  local digest = encode(list(data.map, data.reveal and data.reveal.status, data.reveal and data.reveal.record,
                          data.revealTable and data.revealTable.status, data.revealTable and data.revealTable.records,
                          data.position and data.position.status, data.position and data.position.x,
                          data.position and data.position.z, data.position and data.position.originX,
                          data.gambits and data.gambits.status, data.gambits and data.gambits.sets,
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
                          data.battle and data.battle.status, data.battle and data.battle.flags,
                          data.battle and data.battle.changes))
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
  pcall(editTick)
  writeSnapshot(false)
  event.executeAfterMs(POLL_MS, poll)
end

local function onMapJump(locationId, posIndex, flags, unknown)
  if exiting then return end
  mapJumps = mapJumps + 1
  forceTable = true
  forceGambits = true
  lastJump = { locationId = locationId, posIndex = posIndex, flags = flags, unknown = unknown, clockUs = safeCall(event.getClock) }
  writeSnapshot(true)
end

local function onExit(kind)
  exiting = true
  seq = seq + 1
  local f = io.open(SLOT_FILES[slot], "w")
  if f then
    f:write(encode({ v = PROTOCOL, type = "snapshot", seq = seq, data = { hello = staticHello, session = { exiting = true, exitType = kind, wallTime = os.time() } } }))
    f:close()
  end
end

local function start()
  ensureOutDir()
  revealTable, revealTableClock, forceTable = nil, nil, true
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
