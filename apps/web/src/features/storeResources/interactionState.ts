/** The route is authoritative; never silently act on another hotel for an invalid deep link. */
export function resolveStoreResourceHotelId(hotels: ReadonlyArray<{ id: string }>, requestedHotelId?: string): string {
  if (requestedHotelId) return hotels.some((hotel) => hotel.id === requestedHotelId) ? requestedHotelId : ''
  return hotels[0]?.id ?? ''
}

/** Ignore a response belonging to a replaced file or a departed hotel/assignment. */
export function createImportRequestGuard() {
  let generation = 0
  return {
    start: () => ++generation,
    invalidate: () => { generation += 1 },
    isCurrent: (request: number) => request === generation,
  }
}
