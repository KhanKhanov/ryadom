// Рисует иконки сайта для экрана «Домой» и уведомлений: два силуэта рядом на синем фоне.
// Временные — заменить, когда появится настоящий логотип. Запуск: node scripts/generate-icons.mjs
// Без зависимостей: картинка считается по пикселям, PNG собирается встроенным zlib Node.js.

import { writeFileSync } from 'node:fs'
import { deflateSync, crc32 } from 'node:zlib'

const BACKGROUND = [0x1d, 0x4e, 0xd8] // --primary из src/styles.css
const FOREGROUND = [0xff, 0xff, 0xff]
const SAMPLES = 4 // сглаживание краёв: 4×4 точки на пиксель

/** Лежит ли точка (доли стороны 0..1) на силуэте: голова — круг, плечи — верхняя половина эллипса. */
function insideFigure(x, y, centerX) {
  const head = (x - centerX) ** 2 + (y - 0.36) ** 2 <= 0.1 ** 2
  const shoulders = y <= 0.74 && ((x - centerX) / 0.17) ** 2 + ((y - 0.74) / 0.2) ** 2 <= 1
  return head || shoulders
}

function draw(size) {
  const pixels = Buffer.alloc(size * size * 4)
  for (let py = 0; py < size; py++) {
    for (let px = 0; px < size; px++) {
      let covered = 0
      for (let sy = 0; sy < SAMPLES; sy++) {
        for (let sx = 0; sx < SAMPLES; sx++) {
          const x = (px + (sx + 0.5) / SAMPLES) / size
          const y = (py + (sy + 0.5) / SAMPLES) / size
          if (insideFigure(x, y, 0.37) || insideFigure(x, y, 0.63)) covered++
        }
      }
      const share = covered / SAMPLES ** 2
      const offset = (py * size + px) * 4
      for (let c = 0; c < 3; c++) pixels[offset + c] = Math.round(BACKGROUND[c] * (1 - share) + FOREGROUND[c] * share)
      pixels[offset + 3] = 0xff
    }
  }
  return pixels
}

function chunk(type, data) {
  const length = Buffer.alloc(4)
  length.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body))
  return Buffer.concat([length, body, crc])
}

function png(size) {
  const pixels = draw(size)
  // Каждая строка — байт фильтра (0 — без фильтра) и пиксели RGBA.
  const rows = Buffer.alloc(size * (size * 4 + 1))
  for (let y = 0; y < size; y++) pixels.copy(rows, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4)
  const header = Buffer.alloc(13)
  header.writeUInt32BE(size, 0)
  header.writeUInt32BE(size, 4)
  header[8] = 8 // бит на канал
  header[9] = 6 // RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(rows, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

const output = new URL('../public/', import.meta.url)
for (const [name, size] of [
  ['icon-192.png', 192],
  ['icon-512.png', 512],
  // iPhone и iPad берут иконку для экрана «Домой» отсюда, а не из манифеста.
  ['apple-touch-icon.png', 180],
]) {
  writeFileSync(new URL(name, output), png(size))
  console.log(`public/${name}`)
}
