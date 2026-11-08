import { vi } from "vitest";

/**
 * Stands in for the web the Worker's outgoing fetches reach. Each service's fake (ESPN, Wikidata,
 * Wikipedia, the email sender) answers for its URL prefix through [alsoServe]; a fetch no fake
 * answers for is recorded in [unexpected] and fails, so a test sees the Worker reach somewhere it shouldn't.
 */
export class FakeWeb {
  /** The fetches no fake answers for, which the Worker shouldn't have sent. */
  readonly unexpected: URL[] = [];
  /** The services' fakes, by the URL prefix they answer for. */
  private readonly services: { prefix: string; answer: (url: URL, init?: RequestInit) => Response }[] = [];

  constructor() {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = new URL(input instanceof Request ? input.url : input.toString());
      const service = this.services.find((s) => url.href.startsWith(s.prefix));
      if (service) return service.answer(url, init);
      this.unexpected.push(url);
      throw new Error(`unexpected fetch: ${url}`);
    });
  }

  /** Answers the requests whose URL starts with [prefix] with [answer]. */
  alsoServe(prefix: string, answer: (url: URL, init?: RequestInit) => Response) {
    this.services.push({ prefix, answer });
  }

  restore() {
    vi.restoreAllMocks();
  }
}
