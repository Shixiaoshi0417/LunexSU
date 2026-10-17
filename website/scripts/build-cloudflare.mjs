import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const result = spawnSync(process.execPath, [
  fileURLToPath(new URL('../node_modules/vitepress/bin/vitepress.js', import.meta.url)),
  'build', 'docs', ...process.argv.slice(2)
], {
  cwd: fileURLToPath(new URL('..', import.meta.url)),
  stdio: 'inherit',
  env: {
    ...process.env,
    LUNEXSU_SITE_BASE: '/',
    LUNEXSU_SITE_URL: 'https://lunexsu.shixiaoshi0417.com/'
  }
})
if (result.error) throw result.error
process.exit(result.status ?? 1)
