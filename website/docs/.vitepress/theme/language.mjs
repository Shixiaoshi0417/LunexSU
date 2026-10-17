export const localePreferenceKey = 'lunexsu.locale'
export const supportedLocales = ['root', 'zh_CN', 'zh_TW', 'ja_JP', 'vi_VN', 'id_ID', 'ru_RU', 'pt_BR']

export function browserLocale(languages = []) {
  for (const language of languages) {
    if (typeof language !== 'string') continue
    const parts = language.toLowerCase().replaceAll('_', '-').split('-')
    if (parts[0] === 'zh') {
      if (parts.includes('hant')) return 'zh_TW'
      if (parts.includes('hans')) return 'zh_CN'
      return parts.some(part => ['tw', 'hk', 'mo'].includes(part)) ? 'zh_TW' : 'zh_CN'
    }
    const locales = { en: 'root', ja: 'ja_JP', vi: 'vi_VN', id: 'id_ID', in: 'id_ID', ru: 'ru_RU', pt: 'pt_BR' }
    if (Object.hasOwn(locales, parts[0])) return locales[parts[0]]
  }
  return 'root'
}

export function localeForPath(pathname, base) {
  if (!pathname.startsWith(base)) return undefined
  const prefix = pathname.slice(base.length).split('/')[0]
  return supportedLocales.includes(prefix) && prefix !== 'root' ? prefix : 'root'
}

export function homepageRedirect(location, base, languages, preference) {
  // Explicit document and translated URLs always win over browser preferences.
  if (location.pathname !== base && location.pathname !== `${base}index.html`) return undefined
  const locale = supportedLocales.includes(preference) ? preference : browserLocale(languages)
  if (locale === 'root') return undefined
  return `${base}${locale}/${location.search || ''}${location.hash || ''}`
}

export function installLanguagePreference(browser, base) {
  let storage
  let preference
  try {
    storage = browser.localStorage
    preference = storage.getItem(localePreferenceKey)
  } catch {
    // Private browsing or storage policies must not prevent reading the site.
  }

  const languages = browser.navigator.languages?.length
    ? browser.navigator.languages : [browser.navigator.language]
  const redirect = homepageRedirect(browser.location, base, languages, preference)
  if (redirect) browser.location.replace(redirect)

  // Keep VitePress' desktop/mobile language menus and corresponding-page links.
  // Only an explicit menu choice is persisted, never an automatic detection.
  browser.document.addEventListener('click', event => {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return
    const link = event.target?.closest?.('.VPNavBarTranslations a, .VPNavScreenTranslations a')
    if (!link) return
    const href = link.getAttribute('href')
    if (!href) return
    const target = new URL(href, browser.location.href)
    if (target.origin !== browser.location.origin) return
    const locale = localeForPath(target.pathname, base)
    if (!locale) return
    try {
      storage?.setItem(localePreferenceKey, locale)
    } catch {
      // Navigation still works when preferences cannot be saved.
    }
  }, true)
}
