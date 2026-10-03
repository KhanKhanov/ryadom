// Файлы сайта, которые собираются отдельно от страниц: Service Worker (/sw.js) и манифесты веб-приложения
// на каждом языке (/manifest-ru.webmanifest и т. д.). В разработке (npm run dev) Vite отдаёт их по запросу,
// при сборке (npm run build) кладёт в dist рядом с index.html.

import { build, type Plugin } from 'vite'
import { MANIFEST_LANGUAGES, manifestPath, webManifest } from './src/manifest.ts'

const SERVICE_WORKER_ENTRY = 'src/push/serviceWorker.ts'
const SERVICE_WORKER_PATH = '/sw.js'

export function pwaFiles(): Plugin {
  let root = '.'
  return {
    name: 'ryadom-pwa-files',
    configResolved(config) {
      root = config.root
    },
    configureServer(server) {
      for (const language of MANIFEST_LANGUAGES) {
        server.middlewares.use(manifestPath(language), (_request, response) => {
          response.setHeader('Content-Type', 'application/manifest+json')
          response.end(webManifest(language))
        })
      }
      // Собирается при каждом запросе: правки в коде Service Worker видны после перезагрузки страницы.
      server.middlewares.use(SERVICE_WORKER_PATH, (_request, response, next) => {
        bundleServiceWorker(root, false).then((code) => {
          response.setHeader('Content-Type', 'text/javascript')
          response.end(code)
        }, next)
      })
    },
    async generateBundle() {
      this.emitFile({ type: 'asset', fileName: SERVICE_WORKER_PATH.slice(1), source: await bundleServiceWorker(root, true) })
      for (const language of MANIFEST_LANGUAGES) {
        this.emitFile({ type: 'asset', fileName: manifestPath(language).slice(1), source: webManifest(language) })
      }
    },
  }
}

/**
 * Service Worker — один файл без import: так его понимают все браузеры, в том числе те,
 * что не умеют Service Worker в виде модулей (Firefox до версии 147).
 */
async function bundleServiceWorker(root: string, minify: boolean): Promise<string> {
  const result = await build({
    configFile: false,
    root,
    logLevel: 'warn',
    build: {
      write: false,
      minify,
      lib: { entry: SERVICE_WORKER_ENTRY, formats: ['iife'], name: 'ryadomServiceWorker', fileName: () => 'sw.js' },
    },
  })
  const output = Array.isArray(result) ? result[0] : result
  if (!('output' in output)) throw new Error('Service worker build did not return a bundle')
  return output.output[0].code
}
