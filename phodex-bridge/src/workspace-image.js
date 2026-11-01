// Read-only image previews for the paired mobile client.
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const run = promisify(execFile);
const types = { '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.webp': 'image/webp', '.heic': 'image/heic', '.heif': 'image/heif' };
const inside = (root, file) => { const relative = path.relative(root, file); return relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative); };

async function workspaceReadImage(params) {
  if (typeof params.path !== 'string' || !params.path.trim()) throw new Error('An image path is required.');
  const cwd = await fs.realpath(params.cwd || process.cwd());
  const file = await fs.realpath(path.resolve(cwd, params.path));
  const roots = [cwd, os.tmpdir(), '/tmp', path.join(process.env.CODEX_HOME || path.join(os.homedir(), '.codex'), 'generated_images')];
  const allowed = (await Promise.all(roots.map(root => fs.realpath(root).catch(() => null)))).filter(Boolean);
  if (!allowed.some(root => inside(root, file))) throw new Error('Image is outside the workspace and preview directories.');
  const mimeType = types[path.extname(file).toLowerCase()];
  if (!mimeType) throw new Error('This file type cannot be previewed as an image.');
  const stat = await fs.stat(file);
  if (!stat.isFile() || stat.size > 8 * 1024 * 1024) throw new Error('Image exceeds the 8 MB preview limit.');
  const result = { path: file, fileName: path.basename(file), mimeType, byteLength: stat.size, mtimeMs: stat.mtimeMs };
  if (params.includeData === false) return result;
  let temp;
  try {
    let source = file;
    if (process.platform === 'darwin' && Number.isFinite(params.maxPixelDimension)) {
      const dimension = Math.max(128, Math.min(3200, Math.round(params.maxPixelDimension)));
      temp = await fs.mkdtemp(path.join(os.tmpdir(), 'remodex-preview-'));
      source = path.join(temp, 'preview.png');
      await run('/usr/bin/sips', ['-s', 'format', 'png', '--resampleHeightWidthMax', String(dimension), file, '--out', source], { timeout: 10000 });
      result.mimeType = 'image/png';
      result.previewMaxPixelDimension = dimension;
    }
    const handle = await fs.open(source, 'r');
    try {
      // Bounded read even if a file grows after stat.
      const buffer = Buffer.alloc(2 * 1024 * 1024 + 1);
      let used = 0;
      while (used < buffer.length) {
        const { bytesRead } = await handle.read(buffer, used, buffer.length - used, null);
        if (!bytesRead) break;
        used += bytesRead;
      }
      if (used === buffer.length) throw new Error('Image preview exceeds 2 MB.');
      result.dataBase64 = buffer.subarray(0, used).toString('base64');
    } finally { await handle.close(); }
    return result;
  } finally { if (temp) await fs.rm(temp, { recursive: true, force: true }); }
}
module.exports = { workspaceReadImage };
