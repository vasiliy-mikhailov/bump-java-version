/**
 * EVERY READ GOES THROUGH HERE, so the base path is applied in one place.
 *
 * A fetch written as `/api/bumps` works standalone and 404s the moment a shell mounts this tool at a
 * prefix — and it 404s at runtime, in the browser, on somebody else's deployment. The prefix is
 * baked at build by next.config.ts and read back here.
 */
const BASE = process.env.NEXT_PUBLIC_BASE_PATH ?? ''

export class ApiError extends Error {
  /**
   * THE URL THAT WAS ACTUALLY REQUESTED, not the path that was asked for. Reporting the bare path
   * hides the one fact that names the cause when a base path is wrong.
   */
  constructor(
    readonly status: number,
    readonly url: string,
  ) {
    super(`${url} answered ${status}`)
  }
}

export async function read<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, { ...init, cache: 'no-store' })
  if (!response.ok) {
    throw new ApiError(response.status, `${BASE}${path}`)
  }
  return (await response.json()) as T
}

/** A link within this zone. Same reason as above: the shell owns the prefix. */
export function href(path: string): string {
  return `${BASE}${path}`
}

/**
 * THE SERVER TELLING, RATHER THAN THE PAGE ASKING.
 *
 * Every view here polled. The list refetched a delta every fifteen seconds, which is a
 * fifteen-second lie on a column whose job is saying whether a bump is still moving, and the record
 * did not poll at all: watching a lane work meant pressing refresh.
 *
 * EventSource rather than a socket, because everything on these pages travels one way and the
 * dashboard runs on a server that cannot upgrade a connection. The browser reconnects on its own
 * when one drops, which is the half of a socket this would otherwise have to write.
 *
 * Returns its own teardown, so an effect can hand it straight back to React.
 */
export function live(
  path: string,
  handlers: Record<string, (data: unknown) => void>,
): () => void {
  if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
    return () => undefined
  }
  const source = new EventSource(href(path))
  for (const [name, handle] of Object.entries(handlers)) {
    source.addEventListener(name, (e) => {
      try {
        handle(JSON.parse((e as MessageEvent).data))
      } catch {
        // A malformed frame is one frame, not a broken page: the next one still arrives.
      }
    })
  }
  return () => source.close()
}
