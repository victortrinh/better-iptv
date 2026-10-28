/** One file in a zip archive: its name, and its contents once [read]. */
export interface ZipEntry {
  name: string;
  size: number;
  read(): Promise<Uint8Array>;
}

/** The files in a zip archive (Subdl serves every subtitle as one), from its central directory. Throws on what isn't a zip. */
export function unzip(zip: Uint8Array): ZipEntry[] {
  const view = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
  // The end of central directory record is 22 bytes, plus a comment of up to 64 KB.
  let end = -1;
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 22 - 0xffff); i--) {
    if (view.getUint32(i, true) === 0x06054b50) {
      end = i;
      break;
    }
  }
  if (end < 0) throw new Error("not a zip archive");
  const count = view.getUint16(end + 10, true);
  let at = view.getUint32(end + 16, true);
  const entries: ZipEntry[] = [];
  for (let n = 0; n < count; n++) {
    if (view.getUint32(at, true) !== 0x02014b50) throw new Error("a damaged zip archive");
    const method = view.getUint16(at + 10, true);
    const compressed = view.getUint32(at + 20, true);
    const size = view.getUint32(at + 24, true);
    const nameLength = view.getUint16(at + 28, true);
    const extraLength = view.getUint16(at + 30, true);
    const commentLength = view.getUint16(at + 32, true);
    const local = view.getUint32(at + 42, true);
    const name = new TextDecoder().decode(zip.subarray(at + 46, at + 46 + nameLength));
    at += 46 + nameLength + extraLength + commentLength;
    if (name.endsWith("/")) continue;
    const data = () => {
      // The local header repeats the name and has its own extra field.
      const start = local + 30 + view.getUint16(local + 26, true) + view.getUint16(local + 28, true);
      return zip.subarray(start, start + compressed);
    };
    entries.push({
      name,
      size,
      read: async () => {
        if (method === 0) return data();
        if (method !== 8) throw new Error(`zip compression method ${method}`);
        const inflated = new Blob([data()]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
        return new Uint8Array(await new Response(inflated).arrayBuffer());
      },
    });
  }
  return entries;
}
