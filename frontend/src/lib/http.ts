export class ApiError extends Error {
  status: number
  payload?: unknown

  constructor(status: number, message: string, payload?: unknown) {
    super(message)
    this.status = status
    this.payload = payload
  }
}

interface RequestOptions extends Omit<RequestInit, 'body'> {
  body?: unknown
}

export const request = async <T>(url: string, options: RequestOptions = {}): Promise<T> => {
  const { body, headers, ...rest } = options
  const multipart = body instanceof FormData
  const init: RequestInit = {
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      ...(body !== undefined && !multipart ? { 'Content-Type': 'application/json' } : {}),
      ...(headers as Record<string, string> | undefined),
    },
    ...rest,
  }
  if (body !== undefined) {
    init.body = multipart ? body : typeof body === 'string' ? body : JSON.stringify(body)
  }

  const res = await fetch(url, init)
  const text = await res.text()
  let payload: unknown
  if (text) {
    try { payload = JSON.parse(text) } catch { payload = text }
  }

  if (!res.ok) {
    const msg =
      payload && typeof payload === 'object' && 'message' in (payload as Record<string, unknown>)
        ? String((payload as Record<string, unknown>).message)
        : `request failed: ${res.status}`
    throw new ApiError(res.status, msg, payload)
  }
  return payload as T
}

export const apiGet = <T>(url: string, options?: RequestOptions) =>
  request<T>(url, { ...options, method: 'GET' })

export const apiPost = <T>(url: string, body?: unknown, options?: RequestOptions) =>
  request<T>(url, { ...options, method: 'POST', body })
