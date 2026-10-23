-- SPDX-License-Identifier: MIT
-- Copyright (c) 2026 FFXII Thor Companion contributors
-- Original research prototype by OpenAI Codex for this project. See LICENSE.txt.
-- Algorithm references: RFC8032, FIPS180-4; no third-party source copied.
-- NOT independently audited. Verification only; variable-time, public operands.
-- Lua 5.4 (LUA Script Loader 1.7.1) or 5.5 (2.0.1); both are tested (game-script/tests/test_verify.py runs both).
assert((_VERSION == 'Lua 5.4' or _VERSION == 'Lua 5.5') and math.maxinteger == 0x7fffffffffffffff,
       'requires Lua 5.4 or 5.5 with 64-bit integers')
local M = {}
local K = {
0x428a2f98d728ae22,0x7137449123ef65cd,0xb5c0fbcfec4d3b2f,0xe9b5dba58189dbbc,
0x3956c25bf348b538,0x59f111f1b605d019,0x923f82a4af194f9b,0xab1c5ed5da6d8118,
0xd807aa98a3030242,0x12835b0145706fbe,0x243185be4ee4b28c,0x550c7dc3d5ffb4e2,
0x72be5d74f27b896f,0x80deb1fe3b1696b1,0x9bdc06a725c71235,0xc19bf174cf692694,
0xe49b69c19ef14ad2,0xefbe4786384f25e3,0x0fc19dc68b8cd5b5,0x240ca1cc77ac9c65,
0x2de92c6f592b0275,0x4a7484aa6ea6e483,0x5cb0a9dcbd41fbd4,0x76f988da831153b5,
0x983e5152ee66dfab,0xa831c66d2db43210,0xb00327c898fb213f,0xbf597fc7beef0ee4,
0xc6e00bf33da88fc2,0xd5a79147930aa725,0x06ca6351e003826f,0x142929670a0e6e70,
0x27b70a8546d22ffc,0x2e1b21385c26c926,0x4d2c6dfc5ac42aed,0x53380d139d95b3df,
0x650a73548baf63de,0x766a0abb3c77b2a8,0x81c2c92e47edaee6,0x92722c851482353b,
0xa2bfe8a14cf10364,0xa81a664bbc423001,0xc24b8b70d0f89791,0xc76c51a30654be30,
0xd192e819d6ef5218,0xd69906245565a910,0xf40e35855771202a,0x106aa07032bbd1b8,
0x19a4c116b8d2d0c8,0x1e376c085141ab53,0x2748774cdf8eeb99,0x34b0bcb5e19b48a8,
0x391c0cb3c5c95a63,0x4ed8aa4ae3418acb,0x5b9cca4f7763e373,0x682e6ff3d6b2b8a3,
0x748f82ee5defb2fc,0x78a5636f43172f60,0x84c87814a1f0ab72,0x8cc702081a6439ec,
0x90befffa23631e28,0xa4506cebde82bde9,0xbef9a3f7b2c67915,0xc67178f2e372532b,
0xca273eceea26619c,0xd186b8c721c0c207,0xeada7dd6cde0eb1e,0xf57d4f7fee6ed178,
0x06f067aa72176fba,0x0a637dc5a2c898a6,0x113f9804bef90dae,0x1b710b35131c471b,
0x28db77f523047d84,0x32caab7b40c72493,0x3c9ebe0a15c9bebc,0x431d67c49c100d4c,
0x4cc5d4becb3e42b6,0x597f299cfc657e2a,0x5fcb6fab3ad6faec,0x6c44198c4a475817}
local function rr(x,n) return (x >> n) | (x << (64-n)) end
function M.sha512(s)
  assert(type(s)=='string' and #s<=2*1024*1024,'message cap')
  local n=#s
  s=s..'\128'..string.rep('\0',(111-n)%128)..string.pack('>i8i8',0,n*8)
  local H={0x6a09e667f3bcc908,0xbb67ae8584caa73b,0x3c6ef372fe94f82b,0xa54ff53a5f1d36f1,
           0x510e527fade682d1,0x9b05688c2b3e6c1f,0x1f83d9abfb41bd6b,0x5be0cd19137e2179}
  for off=1,#s,128 do
    local w={}
    for i=1,16 do w[i]=string.unpack('>i8',s,off+(i-1)*8) end
    for i=17,80 do
      local x,y=w[i-15],w[i-2]
      w[i]=w[i-16]+(rr(x,1)~rr(x,8)~(x>>7))+w[i-7]+(rr(y,19)~rr(y,61)~(y>>6))
    end
    local a,b,c,d,e,f,g,h=table.unpack(H)
    for i=1,80 do
      local t=h+(rr(e,14)~rr(e,18)~rr(e,41))+((e&f)~((~e)&g))+K[i]+w[i]
      local u=(rr(a,28)~rr(a,34)~rr(a,39))+((a&b)~(a&c)~(b&c))
      h,g,f,e,d,c,b,a=g,f,e,d+t,c,b,a,t+u
    end
    local v={a,b,c,d,e,f,g,h}
    for i=1,8 do H[i]=H[i]+v[i] end
  end
  local out={}; for i=1,8 do out[i]=string.pack('>i8',H[i]) end
  return table.concat(out)
end
-- F_p, radix 2^15, 17 limbs. Products <17*2^30; fold B^17=19 mod p.
local B=32768
local function small(n) local t={}; for i=1,17 do t[i]=0 end; t[1]=n; return t end
local ZERO,ONE=small(0),small(1)
local function norm(t)
  for _=1,3 do
    for i=1,16 do local c=t[i]//B;t[i]=t[i]-c*B;t[i+1]=t[i+1]+c end
    local c=t[17]//B;t[17]=t[17]-c*B;t[1]=t[1]+19*c
  end
  local ge=t[1]>=B-19
  for i=2,17 do ge=ge and t[i]==B-1 end
  if ge then t[1]=t[1]-(B-19);for i=2,17 do t[i]=0 end end
  return t
end
local function add(a,b) local t={};for i=1,17 do t[i]=a[i]+b[i] end;return norm(t) end
local function sub(a,b) local t={};for i=1,17 do t[i]=a[i]-b[i] end;return norm(t) end
local function mul(a,b)
  local t={};for i=1,33 do t[i]=0 end
  for i=1,17 do for j=1,17 do t[i+j-1]=t[i+j-1]+a[i]*b[j] end end
  for i=33,18,-1 do t[i-17]=t[i-17]+19*t[i] end
  return norm(t)
end
local function eq(a,b) for i=1,17 do if a[i]~=b[i] then return false end end;return true end
local function power(a,exponent,top)
  local r=ONE
  for bit=top,0,-1 do
    r=mul(r,r)
    if (exponent:byte(bit//8+1)>>(bit%8))&1==1 then r=mul(r,a) end
  end
  return r
end
local function unhex(s) return (s:gsub('..',function(v)return string.char(tonumber(v,16))end)) end
local P=unhex('ed'..string.rep('ff',30)..'7f')
local L=unhex('edd3f55c1a631258d69cf7a2def9de14'..string.rep('00',15)..'10')
local function less(a,b) for i=#a,1,-1 do if a:byte(i)~=b:byte(i) then return a:byte(i)<b:byte(i) end end;return false end
local function frombytes(s)
  local t=small(0)
  for bit=0,254 do if ((s:byte(bit//8+1)>>(bit%8))&1)==1 then local i=bit//15+1;t[i]=t[i]+(1<<(bit%15)) end end
  return t
end
local D=frombytes(unhex('a3785913ca4deb75abd841414d0a700098e879777940c78c73fe6f2bee6c0352'))
local I=frombytes(unhex('b0a00e4a271beec478e42fad0618432fa7d7fb3d99004d2b0bdfc14f8024832b'))
local INV=unhex('eb'..string.rep('ff',30)..'7f')
local SQRT=unhex('fe'..string.rep('ff',30)..'0f')
local function identity()return {ZERO,ONE,ONE,ZERO}end
-- Complete extended Edwards addition for a=-1.
local function padd(p,q)
  local a=mul(sub(p[2],p[1]),sub(q[2],q[1]))
  local b=mul(add(p[2],p[1]),add(q[2],q[1]))
  local c=mul(add(D,D),mul(p[4],q[4]))
  local d=add(mul(p[3],q[3]),mul(p[3],q[3]))
  local e,f,g,h=sub(b,a),sub(d,c),add(d,c),add(b,a)
  return {mul(e,f),mul(g,h),mul(f,g),mul(e,h)}
end
local function smul(p,s)
  local r=identity()
  for bit=#s*8-1,0,-1 do
    r=padd(r,r)
    if ((s:byte(bit//8+1)>>(bit%8))&1)==1 then r=padd(r,p) end
  end
  return r
end
local function peq(p,q)return eq(mul(p[1],q[3]),mul(q[1],p[3])) and eq(mul(p[2],q[3]),mul(q[2],p[3]))end
local function decode(s)
  if #s~=32 then return nil end
  local sign=s:byte(32)>>7
  local raw=s:sub(1,31)..string.char(s:byte(32)&127)
  if not less(raw,P) then return nil end
  local y=frombytes(raw);local y2=mul(y,y)
  local x2=mul(sub(y2,ONE),power(add(mul(D,y2),ONE),INV,254))
  local x=power(x2,SQRT,251)
  if not eq(mul(x,x),x2) then x=mul(x,I) end
  if not eq(mul(x,x),x2) or (eq(x,ZERO) and sign==1) then return nil end
  if (x[1]&1)~=sign then x=sub(ZERO,x) end
  return {x,y,ONE,mul(x,y)}
end
local BASE=assert(decode(unhex('58'..string.rep('66',31))))
function M.verify(pk,message,sig)
  if type(pk)~='string' or type(message)~='string' or type(sig)~='string'
     or #pk~=32 or #sig~=64 or #message>1024*1024 then return false end
  local rb,s=sig:sub(1,32),sig:sub(33)
  if not less(s,L) then return false end
  local a,r=decode(pk),decode(rb)
  if not a or not r then return false end
  -- Strict policy: nonidentity prime-order A and R, rejects torsion/mixed-order points.
  local id=identity()
  if peq(a,id) or peq(r,id) or not peq(smul(a,L),id) or not peq(smul(r,L),id) then return false end
  local h=M.sha512(rb..pk..message)
  -- h need not be reduced: A has been checked to have prime order L.
  return peq(smul(BASE,s),padd(r,smul(a,h)))
end
return M
