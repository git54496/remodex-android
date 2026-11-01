const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { workspaceReadImage } = require('../src/workspace-image');

test('workspace image preview reads bytes, metadata, and rejects unsupported files', async () => {
  const cwd = await fs.mkdtemp(path.join(os.tmpdir(), 'image-test-'));
  try {
    const bytes = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1kAAAAASUVORK5CYII=', 'base64');
    await fs.writeFile(path.join(cwd, 'image.png'), bytes);
    const result = await workspaceReadImage({cwd, path: 'image.png'});
    assert.equal(result.dataBase64, bytes.toString('base64'));
    assert.equal(result.mimeType, 'image/png');
    assert.equal((await workspaceReadImage({cwd, path: 'image.png', includeData: false})).dataBase64, undefined);
    await fs.writeFile(path.join(cwd, 'text.txt'), 'secret');
    await assert.rejects(workspaceReadImage({cwd, path: 'text.txt'}), /file type/);
    await assert.rejects(workspaceReadImage({cwd, path: '/etc/hosts'}), /outside/);
    await fs.writeFile(path.join(cwd, 'large.png'), Buffer.alloc(2 * 1024 * 1024 + 1));
    await assert.rejects(workspaceReadImage({cwd, path: 'large.png'}), /exceeds 2 MB/);
  } finally { await fs.rm(cwd, {recursive: true, force: true}); }
});
