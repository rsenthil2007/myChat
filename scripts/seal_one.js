#!/usr/bin/env node
/** Seal one text payload for room; print envelope JSON to stdout. */
const fs = require("fs");
const vm = require("vm");
const path = require("path");

const room = process.argv[2] || "lobby";
const text = process.argv[3] || "hi";
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
  CompressionStream: undefined,
  DecompressionStream: undefined
};
vm.createContext(ctx);
vm.runInContext(
  fs.readFileSync(path.join(root, "js/crypto-pipe.js"), "utf8").replace("const SecurePipe", "var SecurePipe") +
    "; this.SecurePipe = SecurePipe;",
  ctx
);

(async () => {
  const env = await ctx.SecurePipe.seal({ text }, room);
  process.stdout.write(JSON.stringify(env));
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
