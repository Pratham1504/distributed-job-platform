import { createWriteStream } from "node:fs";
import { mkdir, stat } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const outputDirectory = resolve(scriptDirectory, "..", "sample-data");
const recordCount = 99_000;
const duplicateRowCount = 20_000;
const invalidRowCount = 10_000;
const uploadLimitBytes = 10 * 1024 * 1024;

async function writeFixture(filename, noteLength) {
  const path = resolve(outputDirectory, filename);
  const output = createWriteStream(path, { encoding: "utf8" });
  output.write("record_id,customer_name,region,note\n");
  const note = "x".repeat(noteLength);
  for (let record = 1; record <= recordCount; record += 1) {
    const duplicate = record <= duplicateRowCount;
    const invalid = record > duplicateRowCount && record <= duplicateRowCount + invalidRowCount;
    const identifier = duplicate
      ? `DUP-${String(Math.ceil(record / 2)).padStart(5, "0")}`
      : `REC-${String(record).padStart(6, "0")}`;
    const customer = invalid ? "" : "Ada Lovelace";
    const accepted = output.write(`${identifier},${customer},North,${note}\n`);
    if (!accepted) await new Promise(resolveWrite => output.once("drain", resolveWrite));
  }
  await new Promise((resolveWrite, rejectWrite) => output.end(error => error ? rejectWrite(error) : resolveWrite()));
  return { filename, bytes: (await stat(path)).size };
}

await mkdir(outputDirectory, { recursive: true });
const belowLimit = await writeFixture("near-upload-limit.csv", 64);
const aboveLimit = await writeFixture("over-upload-limit.csv", 77);
if (belowLimit.bytes >= uploadLimitBytes || aboveLimit.bytes <= uploadLimitBytes) {
  throw new Error("Generated fixture sizes do not straddle the configured 10 MiB upload limit.");
}
console.log(JSON.stringify({
  belowLimit,
  aboveLimit,
  uploadLimitBytes,
  processingExpectation: {
    totalRows: recordCount,
    validRows: recordCount - invalidRowCount,
    rejectedRows: invalidRowCount,
    duplicatesRemoved: duplicateRowCount / 2
  }
}));
