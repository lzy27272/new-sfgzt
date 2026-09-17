import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { imageOrientation } from '../src/app/imageEvidence.ts'

const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8')
const pageSource = readFileSync(new URL('../src/P0Pages.tsx', import.meta.url), 'utf8')
const previewSource = readFileSync(new URL('../src/shared/AttachmentPreviewDialog.tsx', import.meta.url), 'utf8')
const stylesSource = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')
const serviceSource = readFileSync(new URL('../../core-api/src/main/java/cn/sifangguan/hotelaios/workdata/AttachmentService.java', import.meta.url), 'utf8')

test('image dimensions classify landscape, portrait and square evidence', () => {
  assert.equal(imageOrientation({ width: 1600, height: 900 }), 'LANDSCAPE')
  assert.equal(imageOrientation({ width: 900, height: 1600 }), 'PORTRAIT')
  assert.equal(imageOrientation({ width: 1000, height: 1000 }), 'SQUARE')
})

test('work-record and task evidence selectors accept readable portrait and landscape photos', () => {
  assert.match(appSource, /validateImageEvidence/)
  assert.match(appSource, /照片可横拍或竖拍/)
  assert.match(appSource, /照片支持横拍或竖拍；支持 JPG、PNG、PDF、Word、Excel/)
  assert.doesNotMatch(appSource, /照片必须横向/)
  assert.match(appSource, /disabled=\{!!saving \|\| validatingAttachments\}/)
})

test('backend normalizes EXIF, safely re-encodes, and records the actual display orientation', () => {
  assert.match(serviceSource, /jpegExifOrientation\(content\)/)
  assert.match(serviceSource, /normalizeImageOrientation\(sourceImage, exifOrientation, mediaType\)/)
  assert.match(serviceSource, /orientationPolicy", "ADAPTIVE"/)
  assert.match(serviceSource, /displayOrientation", imageOrientation/)
  assert.doesNotMatch(serviceSource, /照片必须横向拍摄/)
})

test('team review gallery uses normalized dimensions with natural-size fallback', () => {
  assert.match(pageSource, /className="evidence-gallery"/)
  assert.match(pageSource, /evidenceContext\(item, expectation\)/)
  assert.match(pageSource, /Math\.min\(4, imageAttachments\.length\)/)
  assert.match(pageSource, /item\.normalizedWidth && item\.normalizedHeight/)
  assert.match(pageSource, /naturalWidth: width, naturalHeight: height/)
  assert.match(pageSource, /竖拍照片以竖向画框陈列，横拍照片以横向画框陈列/)
  assert.match(stylesSource, /evidence-orientation-portrait/)
  assert.match(pageSource, /上一张图片/)
  assert.match(pageSource, /下一张图片/)
})

test('task evidence lightbox switches its frame to the loaded image orientation', () => {
  assert.match(previewSource, /naturalWidth: width, naturalHeight: height/)
  assert.match(previewSource, /attachment-lightbox-\$\{orientation\}/)
  assert.match(stylesSource, /attachment-lightbox\.attachment-lightbox-portrait/)
  assert.match(stylesSource, /attachment-lightbox\.attachment-lightbox-square/)
})
