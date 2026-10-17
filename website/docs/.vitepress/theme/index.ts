import type { Theme } from 'vitepress'
import DefaultTheme from 'vitepress/theme'
import { installLanguagePreference } from './language.mjs'

export default {
  extends: DefaultTheme,
  enhanceApp({ siteData }) {
    if (!import.meta.env.SSR) {
      installLanguagePreference(window, siteData.value.base)
    }
  }
} satisfies Theme
