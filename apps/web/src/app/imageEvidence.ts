const SUPPORTED_IMAGE_TYPES = new Set(['image/jpeg', 'image/png'])
const SUPPORTED_IMAGE_NAME = /\.(?:jpe?g|png)$/i
const IMAGE_NAME = /\.(?:avif|bmp|gif|heic|heif|jpe?g|png|tiff?|webp)$/i

export type ImageDimensions = { width: number; height: number }
export type ImageOrientation = 'LANDSCAPE' | 'PORTRAIT' | 'SQUARE'

export type ImageEvidenceValidation<T> = {
  accepted: T[]
  rejected: Array<{ entry: T; reason: string }>
}

export function imageOrientation({ width, height }: ImageDimensions): ImageOrientation {
  if (width > height) return 'LANDSCAPE'
  if (height > width) return 'PORTRAIT'
  return 'SQUARE'
}

function looksLikeImage(file: Pick<File, 'name' | 'type'>) {
  return file.type.toLowerCase().startsWith('image/') || IMAGE_NAME.test(file.name)
}

function isSupportedImage(file: Pick<File, 'name' | 'type'>) {
  const normalizedType = file.type.toLowerCase().split(';', 1)[0]
  return SUPPORTED_IMAGE_TYPES.has(normalizedType) || (!normalizedType && SUPPORTED_IMAGE_NAME.test(file.name))
}

async function browserImageDimensions(file: File): Promise<ImageDimensions> {
  if (typeof createImageBitmap === 'function') {
    const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
    try {
      return { width: bitmap.width, height: bitmap.height }
    } finally {
      bitmap.close()
    }
  }

  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const image = new Image()
    image.onload = () => {
      URL.revokeObjectURL(url)
      resolve({ width: image.naturalWidth, height: image.naturalHeight })
    }
    image.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new Error('无法读取图片尺寸'))
    }
    image.src = url
  })
}

export async function validateImageEvidence<T extends { file: File }>(entries: T[]): Promise<ImageEvidenceValidation<T>> {
  const results = await Promise.all(entries.map(async (entry) => {
    const { file } = entry
    if (!looksLikeImage(file)) return { entry, accepted: true, reason: '' }
    if (!isSupportedImage(file)) {
      return { entry, accepted: false, reason: '照片仅支持 JPG 或 PNG 格式' }
    }
    try {
      await browserImageDimensions(file)
      return { entry, accepted: true, reason: '' }
    } catch {
      return { entry, accepted: false, reason: '无法读取图片，请重新拍摄或选择 JPG、PNG 照片' }
    }
  }))

  return {
    accepted: results.filter((result) => result.accepted).map((result) => result.entry),
    rejected: results.filter((result) => !result.accepted).map((result) => ({ entry: result.entry, reason: result.reason })),
  }
}
