import { defineConfig, SiteConfig } from 'vitepress'
import locales from './locales'
import { readdir, writeFile } from 'fs/promises'
import { resolve } from 'path'

export default defineConfig( {
    title: 'LunexSU',
    base: process.env.LUNEXSU_SITE_BASE || '/LunexSU/',
    locales: locales.locales,
    head: [
        ['link', { rel: 'icon', href: '/favicon.ico' }],
    ],
    sitemap: {
        hostname: process.env.LUNEXSU_SITE_URL || 'https://shixiaoshi0417.github.io/LunexSU/'
    },
    buildEnd: async (config: SiteConfig) => {
        const templateDir = resolve(config.outDir, 'templates');
        const templateList = resolve(templateDir, "index.json");
        let files = [];
        try {
            files = await readdir(templateDir);
            files = files.filter(file => !file.startsWith('.'));
        } catch(e) {
            // ignore
        }
        await writeFile(templateList, JSON.stringify(files));
    }
})
