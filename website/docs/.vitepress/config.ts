import { defineConfig, SiteConfig } from 'vitepress'
import locales from './locales'
import { readdir, writeFile } from 'fs/promises'
import { resolve } from 'path'

const base = process.env.LUNEXSU_SITE_BASE || '/LunexSU/'

export default defineConfig( {
    title: 'LunexSU',
    base,
    locales: locales.locales,
    head: [
        // head entries are not rewritten with base, unlike Markdown links.
        ['link', { rel: 'icon', href: `${base}favicon.ico` }],
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
