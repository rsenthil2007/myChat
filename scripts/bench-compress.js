/**
 * Benchmark deflate-raw ratios for myChat payload types.
 * Run: node scripts/bench-compress.js
 */
const { Blob } = require("buffer");

async function deflateRaw(bytes) {
  const stream = new Blob([bytes]).stream().pipeThrough(new CompressionStream("deflate-raw"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

function b64size(n) {
  return Math.ceil(n / 3) * 4;
}

async function measure(label, obj) {
  const te = new TextEncoder();
  const raw = te.encode(JSON.stringify(obj));
  const compressed = await deflateRaw(raw);
  const keep = compressed.length < raw.length - 8;
  const used = keep ? compressed : raw;
  const ratio = raw.length / used.length;
  const saved = ((1 - used.length / raw.length) * 100).toFixed(1);
  console.log(
    `${label.padEnd(28)} raw=${String(raw.length).padStart(7)}  ` +
      `zip=${String(compressed.length).padStart(7)}  ` +
      `used=${String(used.length).padStart(7)}  ` +
      `ratio=${ratio.toFixed(2)}x  saved=${keep ? saved : "0 (skip)"}%  ` +
      `wire≈${b64size(used.length)} b64`
  );
  return { raw: raw.length, zip: compressed.length, used: used.length, ratio, keep };
}

(async () => {
  console.log("myChat compression expectations (deflate-raw on JSON before encrypt)\n");

  await measure("text short Hi", { text: "Hi" });
  await measure("text sentence", {
    text: "Please explain the quadratic formula step by step."
  });
  await measure("text paragraph", {
    text:
      "In this tutoring session we will solve simultaneous equations. " +
      "First isolate x, then substitute into the second equation. " +
      "Check your answer by plugging back into both originals."
  });
  await measure("text repetitive notes", {
    text: ("note: review chapter 4. ").repeat(40)
  });

  // Sparse doodle ~2 seconds of drawing
  const shortStroke = {
    w: 320,
    h: 480,
    strokes: [
      {
        c: "#0f172a",
        s: 4,
        p: Array.from({ length: 80 }, (_, i) => (i % 2 === 0 ? 40 + i : 100 + (i % 17)))
      }
    ]
  };
  await measure("drawing small doodle", shortStroke);

  // Dense sketch
  const denseStroke = {
    w: 360,
    h: 640,
    strokes: Array.from({ length: 8 }, (_, s) => ({
      c: s % 2 ? "#0d9488" : "#0f172a",
      s: 3 + (s % 3),
      p: Array.from({ length: 300 }, (_, i) => Math.round((Math.sin(i / 8 + s) + 1) * 120 + i * 0.2))
    }))
  };
  await measure("drawing dense sketch", denseStroke);

  // Audio: Opus/WebM is already compressed — we only wrap base64 in JSON
  const fakeWebm = Buffer.alloc(8 * 1024, 0x1a); // incompressible-ish binary
  for (let i = 0; i < fakeWebm.length; i++) fakeWebm[i] = (i * 17 + 31) & 0xff;
  const audioShort = {
    mime: "audio/webm;codecs=opus",
    audio: fakeWebm.toString("base64")
  };
  await measure("audio ~8KB webm wrap", audioShort);

  const fakeWebm30 = Buffer.alloc(30 * 1024, 0);
  for (let i = 0; i < fakeWebm30.length; i++) fakeWebm30[i] = (Math.sin(i) * 1e6) & 0xff;
  await measure("audio ~30KB webm wrap", {
    mime: "audio/webm;codecs=opus",
    audio: fakeWebm30.toString("base64")
  });

  // Real-ish: base64 of zeros compresses a lot (best case); random = worst case
  await measure("audio zeros best-case", {
    mime: "audio/webm",
    audio: Buffer.alloc(20 * 1024, 0).toString("base64")
  });

  console.log("\nNotes:");
  console.log("- Ratio = raw JSON / payload we keep (after optional deflate).");
  console.log("- App skips deflate if it does not shrink enough (short texts).");
  console.log("- Wire size is also base64(~4/3) then encrypted (same length as used).");
})();
