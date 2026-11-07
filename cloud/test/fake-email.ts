import type { FakeTmdb } from "./fake-tmdb";

/** Where the Worker's `RESEND_API` sends email, as wrangler.jsonc says. */
const RESEND = "https://api.resend.com/";

/** One email as the Worker handed it to the transactional sender. */
export interface SentEmail {
  from: string;
  to: string[];
  subject: string;
  text: string;
  html: string;
  authorization: string | null;
}

/** Stands in for Resend's API, served through [tmdb]'s fetch stand-in: it records each email instead of sending it. */
export class FakeEmail {
  readonly sent: SentEmail[] = [];
  /** Answers with this HTTP status instead, as when Resend is down or refuses the key. */
  failWith: number | null = null;

  constructor(tmdb: FakeTmdb) {
    tmdb.alsoServe(RESEND, (url, init) => {
      if (url.pathname !== "/emails" || init?.method !== "POST") return Response.json({ message: "Not found" }, { status: 404 });
      if (this.failWith !== null) return Response.json({ message: "failed" }, { status: this.failWith });
      const body = JSON.parse(init.body as string) as Omit<SentEmail, "authorization">;
      this.sent.push({ ...body, authorization: new Headers(init.headers).get("Authorization") });
      return Response.json({ id: `email-${this.sent.length}` });
    });
  }

  /** The magic link in the last email to [address] (any case, as the cloud sends to it lowercased), or null when none went there. */
  lastLinkTo(address: string): string | null {
    const message = [...this.sent].reverse().find((m) => m.to.includes(address.toLowerCase()));
    return message ? (/https:\/\/\S+\/sign-in\/link\?token=[A-Za-z0-9_-]+/.exec(message.text)?.[0] ?? null) : null;
  }
}
