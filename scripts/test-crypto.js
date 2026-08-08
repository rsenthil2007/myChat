const fs = require("fs");
const vm = require("vm");
const path = require("path");
const { Blob } = require("buffer");

const root = path.join(__dirname, "..");
const ctx = {
  console,
  btoa: (s) => Buffer.from(s, "binary").toString("base64"),
  atob: (s) => Buffer.from(s, "base64").toString("binary"),
  crypto: {
    getRandomValues: (a) => {
      require("crypto").randomFillSync(a);
      return a;
    }
  },
  TextEncoder,
  TextDecoder,
  Blob,
  CompressionStream,
  DecompressionStream,
  Response
};
vm.createContext(ctx);
vm.runInContext(
  fs.readFileSync(path.join(root, "js/crypto-pipe.js"), "utf8").replace("const SecurePipe", "var SecurePipe") +
    "; this.SecurePipe = SecurePipe;",
  ctx
);

function assert(cond, msg) {
  if (!cond) throw new Error(msg || "assert failed");
}

(async () => {
  // Short text — usually uncompressed
  for (const room of ["r1", "r2", "suite-a"]) {
    for (const text of ["Hi", "Hello"]) {
      const env = await ctx.SecurePipe.seal({ text }, room);
      assert(env.v === 3, "expected v3");
      assert(!!env.mac, "mac required");
      const out = await ctx.SecurePipe.open(env, room);
      assert(out.text === text, "short roundtrip");
    }
  }

  // Larger / repetitive payload should compress
  const bigText = ("stroke-point-" + "x".repeat(40) + "-").repeat(30);
  const bigEnv = await ctx.SecurePipe.seal({ text: bigText }, "r1");
  assert(bigEnv.zip === 1, "expected zip=1 for large payload, got " + bigEnv.zip);
  const bigOut = await ctx.SecurePipe.open(bigEnv, "r1");
  assert(bigOut.text === bigText, "compressed roundtrip");

  // Drawing-like strokes compress well
  const strokes = {
    w: 300,
    h: 400,
    strokes: [{ c: "#0f172a", s: 4, p: Array.from({ length: 400 }, (_, i) => i % 50) }]
  };
  const drawEnv = await ctx.SecurePipe.seal(strokes, "r1");
  assert(drawEnv.zip === 1, "drawing should compress");
  const drawOut = await ctx.SecurePipe.open(drawEnv, "r1");
  assert(drawOut.w === 300 && drawOut.strokes.length === 1, "drawing roundtrip");

  // Room key isolation
  const env = await ctx.SecurePipe.seal({ text: "secret-r1" }, "r1");
  let rejected = false;
  try {
    await ctx.SecurePipe.open(env, "r2");
  } catch {
    rejected = true;
  }
  assert(rejected, "r2 must not open r1 ciphertext");

  // Missing mac rejected
  rejected = false;
  try {
    await ctx.SecurePipe.open({ v: 3, zip: 0, iv: env.iv, data: env.data }, "r1");
  } catch {
    rejected = true;
  }
  assert(rejected, "missing mac must fail");

  // Wire JSON stability with compression
  const sealed = await ctx.SecurePipe.seal({ text: bigText }, "r1");
  const wire = JSON.parse(JSON.stringify({ secure: true, ...sealed }));
  assert(wire.zip === 1, "wire keeps zip");
  const opened = await ctx.SecurePipe.open(wire, "r1");
  assert(opened.text === bigText, "wire compressed roundtrip");

  // Savings check
  const rawLen = Buffer.byteLength(JSON.stringify({ text: bigText }));
  const cipherLen = Buffer.from(sealed.data, "base64").length;
  console.log("OK crypto suite");
  console.log("OK compress zip=", sealed.zip, "raw≈", rawLen, "cipher=", cipherLen);
  console.log("OK wire", { text: opened.text.slice(0, 12) + "…" });
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
