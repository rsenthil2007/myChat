/**
 * Compress (optional deflate-raw) then authenticated-encrypt.
 * Uses SHA-256 keystream + HMAC-SHA256 (pure JS) so it works on:
 *   - localhost
 *   - http://192.168.x.x (no crypto.subtle)
 *   - Chrome / Edge / mobile
 */
const SecurePipe = (() => {
  const APP_PEPPER = "mychat-secure-v3";
  const keyCache = new Map();
  const te = new TextEncoder();
  const td = new TextDecoder();

  function b64FromBytes(bytes) {
    let s = "";
    const chunk = 0x8000;
    for (let i = 0; i < bytes.length; i += chunk) {
      s += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
    }
    return btoa(s);
  }

  function bytesFromB64(b64) {
    const bin = atob(String(b64 || ""));
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  function concatBytes(parts) {
    let n = 0;
    for (const p of parts) n += p.length;
    const out = new Uint8Array(n);
    let o = 0;
    for (const p of parts) {
      out.set(p, o);
      o += p.length;
    }
    return out;
  }

  function rotr(n, x) {
    return (x >>> n) | (x << (32 - n));
  }

  function sha256(bytes) {
    const K = new Uint32Array([
      0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
      0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
      0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
      0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
      0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
      0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
      0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
      0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    ]);
    const h = new Uint32Array([
      0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    ]);

    const len = bytes.length;
    const bitLenHi = Math.floor((len * 8) / 0x100000000);
    const bitLenLo = (len * 8) >>> 0;
    const withPad = (len + 9 + 63) & ~63;
    const buf = new Uint8Array(withPad);
    buf.set(bytes);
    buf[len] = 0x80;
    const dv = new DataView(buf.buffer);
    dv.setUint32(withPad - 8, bitLenHi, false);
    dv.setUint32(withPad - 4, bitLenLo, false);

    const w = new Uint32Array(64);
    for (let i = 0; i < withPad; i += 64) {
      for (let j = 0; j < 16; j++) w[j] = dv.getUint32(i + j * 4, false);
      for (let j = 16; j < 64; j++) {
        const s0 = rotr(7, w[j - 15]) ^ rotr(18, w[j - 15]) ^ (w[j - 15] >>> 3);
        const s1 = rotr(17, w[j - 2]) ^ rotr(19, w[j - 2]) ^ (w[j - 2] >>> 10);
        w[j] = (w[j - 16] + s0 + w[j - 7] + s1) >>> 0;
      }
      let a = h[0],
        b = h[1],
        c = h[2],
        d = h[3],
        e = h[4],
        f = h[5],
        g = h[6],
        hh = h[7];
      for (let j = 0; j < 64; j++) {
        const S1 = rotr(6, e) ^ rotr(11, e) ^ rotr(25, e);
        const ch = (e & f) ^ (~e & g);
        const t1 = (hh + S1 + ch + K[j] + w[j]) >>> 0;
        const S0 = rotr(2, a) ^ rotr(13, a) ^ rotr(22, a);
        const maj = (a & b) ^ (a & c) ^ (b & c);
        const t2 = (S0 + maj) >>> 0;
        hh = g;
        g = f;
        f = e;
        e = (d + t1) >>> 0;
        d = c;
        c = b;
        b = a;
        a = (t1 + t2) >>> 0;
      }
      h[0] = (h[0] + a) >>> 0;
      h[1] = (h[1] + b) >>> 0;
      h[2] = (h[2] + c) >>> 0;
      h[3] = (h[3] + d) >>> 0;
      h[4] = (h[4] + e) >>> 0;
      h[5] = (h[5] + f) >>> 0;
      h[6] = (h[6] + g) >>> 0;
      h[7] = (h[7] + hh) >>> 0;
    }

    const out = new Uint8Array(32);
    const odv = new DataView(out.buffer);
    for (let i = 0; i < 8; i++) odv.setUint32(i * 4, h[i], false);
    return out;
  }

  function hmacSha256(keyBytes, msgBytes) {
    const block = 64;
    let key = keyBytes.length > block ? sha256(keyBytes) : keyBytes;
    const k = new Uint8Array(block);
    k.set(key);
    const oPad = new Uint8Array(block);
    const iPad = new Uint8Array(block);
    for (let i = 0; i < block; i++) {
      oPad[i] = k[i] ^ 0x5c;
      iPad[i] = k[i] ^ 0x36;
    }
    return sha256(concatBytes([oPad, sha256(concatBytes([iPad, msgBytes]))]));
  }

  function deriveKeys(roomId) {
    const id = String(roomId || "lobby");
    if (keyCache.has(id)) return keyCache.get(id);

    // Light KDF: iterated SHA-256 over pepper + room (deterministic, no subtle)
    let x = te.encode(`${APP_PEPPER}|${id}|mychat-salt-v3`);
    for (let i = 0; i < 20000; i++) x = sha256(x);
    const encKey = x;
    const macKey = sha256(concatBytes([te.encode("mac|"), encKey]));
    const keys = { encKey, macKey };
    keyCache.set(id, keys);
    return keys;
  }

  function keystream(encKey, iv, length) {
    const out = new Uint8Array(length);
    const counter = new Uint8Array(4);
    let offset = 0;
    let n = 0;
    while (offset < length) {
      counter[0] = (n >>> 24) & 0xff;
      counter[1] = (n >>> 16) & 0xff;
      counter[2] = (n >>> 8) & 0xff;
      counter[3] = n & 0xff;
      const block = sha256(concatBytes([encKey, iv, counter]));
      const take = Math.min(32, length - offset);
      out.set(block.subarray(0, take), offset);
      offset += take;
      n += 1;
    }
    return out;
  }

  function xorBytes(a, b) {
    const out = new Uint8Array(a.length);
    for (let i = 0; i < a.length; i++) out[i] = a[i] ^ b[i];
    return out;
  }

  const MIN_COMPRESS = 64; // bytes — tiny texts often grow after deflate

  async function compress(bytes) {
    if (
      typeof CompressionStream === "undefined" ||
      typeof Blob === "undefined" ||
      typeof Response === "undefined" ||
      bytes.length < MIN_COMPRESS
    ) {
      return { bytes, zip: 0 };
    }
    try {
      const stream = new Blob([bytes]).stream().pipeThrough(new CompressionStream("deflate-raw"));
      const buf = await new Response(stream).arrayBuffer();
      const compressed = new Uint8Array(buf);
      // Keep only when it actually saves bandwidth
      if (compressed.length >= bytes.length - 8) return { bytes, zip: 0 };
      return { bytes: compressed, zip: 1 };
    } catch {
      return { bytes, zip: 0 };
    }
  }

  async function decompress(bytes, zip) {
    if (!zip) return bytes;
    if (
      typeof DecompressionStream === "undefined" ||
      typeof Blob === "undefined" ||
      typeof Response === "undefined"
    ) {
      throw new Error("DecompressionStream missing");
    }
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
    return new Uint8Array(await new Response(stream).arrayBuffer());
  }

  async function seal(plainPayload, roomId) {
    const raw = te.encode(JSON.stringify(plainPayload));
    const { bytes: packed, zip } = await compress(raw);
    const iv = crypto.getRandomValues(new Uint8Array(16));
    const { encKey, macKey } = deriveKeys(roomId);
    const cipher = xorBytes(packed, keystream(encKey, iv, packed.length));
    const mac = hmacSha256(
      macKey,
      concatBytes([Uint8Array.of(zip ? 1 : 0), iv, cipher])
    );
    return {
      v: 3,
      zip,
      iv: b64FromBytes(iv),
      mac: b64FromBytes(mac),
      data: b64FromBytes(cipher)
    };
  }

  async function open(envelope, roomId) {
    if (!envelope || !envelope.iv || !envelope.data || !envelope.mac) {
      throw new Error("Invalid envelope");
    }
    const version = envelope.v || 1;
    if (version < 3) {
      throw new Error("Old message format — clear room and resend");
    }

    const iv = bytesFromB64(envelope.iv);
    const cipher = bytesFromB64(envelope.data);
    const mac = bytesFromB64(envelope.mac);
    const zip = envelope.zip ? 1 : 0;
    const { encKey, macKey } = deriveKeys(roomId);

    const expect = hmacSha256(
      macKey,
      concatBytes([Uint8Array.of(zip), iv, cipher])
    );
    let diff = 0;
    for (let i = 0; i < 32; i++) diff |= expect[i] ^ (mac[i] || 0);
    if (diff !== 0) throw new Error("MAC mismatch");

    const packed = xorBytes(cipher, keystream(encKey, iv, cipher.length));
    const plain = await decompress(packed, zip);
    return JSON.parse(td.decode(plain));
  }

  function clearKeyCache() {
    keyCache.clear();
  }

  return { seal, open, clearKeyCache };
})();
