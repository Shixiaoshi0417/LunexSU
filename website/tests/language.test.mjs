import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  browserLocale, homepageRedirect, installLanguagePreference,
  localeForPath, localePreferenceKey
} from '../docs/.vitepress/theme/language.mjs'

for (const [languages, expected] of [
  [['zh-CN'], 'zh_CN'], [['zh-SG'], 'zh_CN'], [['zh-Hans-TW'], 'zh_CN'],
  [['zh-TW'], 'zh_TW'], [['zh-HK'], 'zh_TW'], [['zh-MO'], 'zh_TW'],
  [['zh-Hant-CN'], 'zh_TW'], [['ZH_hant'], 'zh_TW'], [['zh'], 'zh_CN'],
  [['en-GB', 'zh-CN'], 'root'], [['de-DE', 'ja-JP'], 'ja_JP'],
  [['vi'], 'vi_VN'], [['id-ID'], 'id_ID'], [['in-ID'], 'id_ID'],
  [['ru'], 'ru_RU'], [['pt-PT'], 'pt_BR'], [['fr'], 'root'],
  [[], 'root'], [[null, '__proto__'], 'root']
]) {
  test(`browser preference ${JSON.stringify(languages)}`, () => {
    assert.equal(browserLocale(languages), expected)
  })
}

test('homepage selection preserves query and hash on both deployment bases', () => {
  for (const base of ['/', '/LunexSU/']) {
    for (const pathname of [base, `${base}index.html`]) {
      assert.equal(homepageRedirect({ pathname, search: '?from=test', hash: '#features' },
        base, ['zh-HK']), `${base}zh_TW/?from=test#features`)
    }
  }
})

test('explicit language choices override the browser, including English', () => {
  assert.equal(homepageRedirect({ pathname: '/' }, '/', ['zh-CN'], 'root'), undefined)
  assert.equal(homepageRedirect({ pathname: '/' }, '/', ['en'], 'ja_JP'), '/ja_JP/')
  assert.equal(homepageRedirect({ pathname: '/' }, '/', ['en'], 'https://evil.test'), undefined)
})

test('document, translated and missing-page URLs are never rewritten', () => {
  for (const pathname of ['/guide/installation.html', '/zh_CN/', '/ja_JP/guide/module', '/missing']) {
    assert.equal(homepageRedirect({ pathname }, '/', ['zh-CN'], 'zh_TW'), undefined)
  }
  assert.equal(homepageRedirect({ pathname: '/other/' }, '/LunexSU/', ['zh-CN']), undefined)
})

test('locale extraction is restricted to the configured base', () => {
  assert.equal(localeForPath('/LunexSU/zh_TW/guide/module.html', '/LunexSU/'), 'zh_TW')
  assert.equal(localeForPath('/LunexSU/guide/module.html', '/LunexSU/'), 'root')
  assert.equal(localeForPath('/else/zh_CN/', '/LunexSU/'), undefined)
})

function fakeBrowser({ pathname = '/', languages = ['zh-CN'], preference } = {}) {
  const values = new Map(preference === undefined ? [] : [[localePreferenceKey, preference]])
  const listeners = []
  const redirects = []
  const browser = {
    navigator: { languages, language: languages[0] },
    location: {
      origin: 'https://example.test', pathname, search: '', hash: '',
      href: `https://example.test${pathname}`,
      replace: target => redirects.push(target)
    },
    localStorage: { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value) },
    document: { addEventListener: (name, callback, capture) => {
      assert.equal(name, 'click')
      assert.equal(capture, true)
      listeners.push(callback)
    } }
  }
  const click = (href, { menu = true, ...event } = {}) => {
    for (const listener of listeners) listener({
      button: 0,
      // VitePress handles internal links first; still remember that menu choice.
      defaultPrevented: true,
      target: { closest: selector => {
        assert.equal(selector, '.VPNavBarTranslations a, .VPNavScreenTranslations a')
        return menu ? { getAttribute: () => href } : null
      } },
      ...event
    })
  }
  return { browser, values, redirects, click }
}

test('automatic detection does not persist a manual preference', () => {
  const state = fakeBrowser()
  installLanguagePreference(state.browser, '/')
  assert.deepEqual(state.redirects, ['/zh_CN/'])
  assert.equal(state.values.size, 0)
})

test('a manual English menu choice is remembered on the next homepage visit', () => {
  const state = fakeBrowser({ pathname: '/zh_CN/' })
  installLanguagePreference(state.browser, '/')
  state.click('/')
  assert.equal(state.values.get(localePreferenceKey), 'root')
  state.browser.location.pathname = '/'
  installLanguagePreference(state.browser, '/')
  assert.deepEqual(state.redirects, [])
})

test('manual translated-page choices are remembered, ordinary links are not', () => {
  const state = fakeBrowser({ pathname: '/LunexSU/guide/module.html' })
  installLanguagePreference(state.browser, '/LunexSU/')
  state.click('/LunexSU/ja_JP/guide/module.html')
  assert.equal(state.values.get(localePreferenceKey), 'ja_JP')
  state.click('/LunexSU/ru_RU/', { menu: false })
  state.click('https://external.test/LunexSU/ru_RU/')
  state.click('/else/ru_RU/')
  state.click('/LunexSU/ru_RU/', { ctrlKey: true })
  assert.equal(state.values.get(localePreferenceKey), 'ja_JP')
})

test('blocked storage and navigator.language fallback do not break navigation', () => {
  const state = fakeBrowser()
  state.browser.navigator = { language: 'zh-Hant' }
  Object.defineProperty(state.browser, 'localStorage', { get() { throw Error('blocked') } })
  assert.doesNotThrow(() => installLanguagePreference(state.browser, '/'))
  assert.deepEqual(state.redirects, ['/zh_TW/'])
  assert.doesNotThrow(() => state.click('/'))
})

test('a storage write failure does not block a manual language switch', () => {
  const state = fakeBrowser({ pathname: '/zh_CN/' })
  state.browser.localStorage.setItem = () => { throw Error('quota') }
  installLanguagePreference(state.browser, '/')
  assert.doesNotThrow(() => state.click('/'))
})
