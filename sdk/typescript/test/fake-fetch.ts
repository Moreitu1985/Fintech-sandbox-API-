/** A scripted stand-in for fetch: answers from a queue and records every request. */
export interface Recorded {
  url: URL;
  method: string;
  headers: Headers;
  body: string | undefined;
}

type Reply = Response | Error | ((req: Recorded) => Response | Promise<Response>);

export class FakeFetch {
  readonly requests: Recorded[] = [];
  private readonly replies: Reply[] = [];

  reply(...replies: Reply[]): this {
    this.replies.push(...replies);
    return this;
  }

  readonly fetch: typeof fetch = async (input, init) => {
    const req: Recorded = {
      url: new URL(String(input)),
      method: init?.method ?? "GET",
      headers: new Headers(init?.headers),
      body: typeof init?.body === "string" ? init.body : undefined,
    };
    this.requests.push(req);
    const signal = init?.signal ?? undefined;
    if (signal?.aborted) throw signal.reason;
    const next = this.replies.shift();
    if (next === undefined) throw new Error(`Unexpected request ${req.method} ${req.url.pathname}`);
    if (next instanceof Error) throw next;
    return typeof next === "function" ? next(req) : next;
  };
}

export function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status, headers: { "Content-Type": "application/json", ...headers },
  });
}

export function problem(status: number, code: string, detail: string, extra: Record<string, unknown> = {},
                        headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify({ type: "about:blank", title: "Error", status, detail, code,
    requestId: "req-123", ...extra }), { status, headers: { "Content-Type": "application/problem+json", ...headers } });
}
