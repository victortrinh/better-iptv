/**
 * The sign-in page the phone opens from the TV's QR (ADR 0015): small static HTML the Worker serves on
 * the app's own domain, with no script and nothing loaded from elsewhere. Verre's colours (DESIGN.md):
 * near-black ground, white type, translucent white glass, solid white only for the button.
 */

/** The one-paragraph privacy note the page carries (roadmap, M5). */
export const PRIVACY_NOTE =
  "We keep your email address, to send you sign-in links; the history you watch, so it follows you to your other TVs; and the list of TVs signed in. " +
  "We never keep your TV service's login: each TV types its own, and it never leaves the TV. Nothing is sold or shared.";

/** The page asking for the TV's code and an email, with [code] filled in when the QR carried one. */
export function signInPage(code: string, message?: { text: string; error: boolean }): string {
  return page(
    "Sign in to Zugig",
    `<h1>Sign in to Zugig</h1>
<p>Type the code your TV shows and your email. We'll send you a link: tap it, and the TV signs in.</p>
${message ? `<p class="${message.error ? "error" : "note"}">${escape(message.text)}</p>` : ""}
<form method="post" action="/sign-in">
<label for="code">Code on the TV</label>
<input id="code" name="code" value="${escape(code)}" required maxlength="8" autocapitalize="characters" autocomplete="off" spellcheck="false" inputmode="text">
<label for="email">Your email</label>
<input id="email" name="email" type="email" required autocomplete="email" maxlength="254">
<button type="submit">Send me a link</button>
</form>
<p class="aside"><a href="/reset-pin">Reset the Household PIN</a> · <a href="/delete-account">Delete my account</a></p>`,
  );
}

/** "Delete my account" (#434): an email, to which a link that deletes the Account is sent. */
export function deletePage(message?: { text: string; error: boolean }): string {
  return page(
    "Delete my account",
    `<h1>Delete my account</h1>
<p>Type your account's email. We'll send you a link: tap it, and the account is deleted with the history it keeps and its list of TVs. Each TV is signed out and keeps working without an account.</p>
${message ? `<p class="${message.error ? "error" : "note"}">${escape(message.text)}</p>` : ""}
<form method="post" action="/delete-account">
<label for="email">Your email</label>
<input id="email" name="email" type="email" required autocomplete="email" maxlength="254">
<button type="submit">Send me a link</button>
</form>`,
  );
}

/** "Reset the Household PIN" (#440): an email, to which a link that clears the Account's PIN is sent. */
export function resetPinPage(message?: { text: string; error: boolean }): string {
  return page(
    "Reset the Household PIN",
    `<h1>Reset the Household PIN</h1>
<p>Forgot the PIN that lets you leave a Kids profile? Type your account's email. We'll send you a link: tap it, and each TV asks for a new PIN the next time someone leaves a Kids profile.</p>
${message ? `<p class="${message.error ? "error" : "note"}">${escape(message.text)}</p>` : ""}
<form method="post" action="/reset-pin">
<label for="email">Your email</label>
<input id="email" name="email" type="email" required autocomplete="email" maxlength="254">
<button type="submit">Send me a link</button>
</form>`,
  );
}

/** The page a reset link opens: one button, so a mail scanner opening the link resets nothing. */
export function confirmResetPinPage(token: string): string {
  return page(
    "Reset the Household PIN",
    `<h1>Reset the Household PIN</h1>
<p>This clears the Household PIN on every TV signed in to your account. The next time someone leaves a Kids profile, the TV asks for a new one.</p>
<form method="post" action="/reset-pin/link">
<input type="hidden" name="token" value="${escape(token)}">
<button type="submit">Reset the PIN</button>
</form>`,
    false,
  );
}

/** The page a delete link opens: one button, so a mail scanner opening the link deletes nothing. */
export function confirmDeletePage(token: string): string {
  return page(
    "Delete my account",
    `<h1>Delete my account</h1>
<p>This deletes your account, the history it keeps and its list of TVs. It can't be undone.</p>
<form method="post" action="/delete-account/link">
<input type="hidden" name="token" value="${escape(token)}">
<button type="submit">Delete my account</button>
</form>`,
    false,
  );
}

/** The page a magic link opens: one button, so a mail scanner opening the link signs nobody in. */
export function confirmPage(token: string): string {
  return page(
    "Sign in the TV",
    `<h1>Sign in the TV</h1>
<p>Tap the button to sign the TV in.</p>
<form method="post" action="/sign-in/link">
<input type="hidden" name="token" value="${escape(token)}">
<button type="submit">Sign in the TV</button>
</form>`,
    false,
  );
}

/** A page with just [text] under [title]. */
export function messagePage(title: string, text: string): string {
  return page(title, `<h1>${escape(title)}</h1>\n<p>${escape(text)}</p>`, false);
}

export function htmlResponse(body: string, status = 200): Response {
  return new Response(body, {
    status,
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      // No script, no frames, nothing from elsewhere; forms post only here.
      "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
      "Referrer-Policy": "no-referrer",
      "Cache-Control": "no-store",
    },
  });
}

function page(title: string, content: string, privacy = true): string {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>${escape(title)}</title>
<style>
:root { color-scheme: dark; }
body { margin: 0; background: #07080A; color: #FFFFFF; font: 17px/1.5 Inter, -apple-system, system-ui, sans-serif; }
main { max-width: 26rem; margin: 0 auto; padding: 3rem 1.25rem; }
h1 { font-size: 1.75rem; letter-spacing: -0.02em; margin: 0 0 0.75rem; }
p { color: #A9A9AF; margin: 0 0 1.25rem; }
label { display: block; font-size: 0.875rem; color: #A9A9AF; margin: 1rem 0 0.375rem; }
input { box-sizing: border-box; width: 100%; padding: 0.875rem 1rem; border: 1.5px solid transparent; border-radius: 12px; background: rgba(255,255,255,0.12); color: #FFFFFF; font: inherit; }
input:focus { outline: none; border-color: #FFFFFF; background: rgba(255,255,255,0.20); }
#code { font-size: 1.5rem; letter-spacing: 0.25em; text-transform: uppercase; }
button { width: 100%; margin-top: 1.5rem; padding: 0.875rem; border: 0; border-radius: 999px; background: #FFFFFF; color: #000000; font: inherit; font-weight: 600; }
.error { color: #FF6B60; }
.note { color: #FFFFFF; }
a { color: #FFFFFF; }
.aside { margin-top: 1.5rem; text-align: center; font-size: 0.875rem; }
small { display: block; margin-top: 2.5rem; color: #A9A9AF; font-size: 0.8125rem; }
</style>
</head>
<body>
<main>
${content}
${privacy ? `<small>${escape(PRIVACY_NOTE)}</small>` : ""}
</main>
</body>
</html>
`;
}

function escape(text: string): string {
  // Attributes are double-quoted, so an apostrophe can stay.
  return text.replace(/[&<>"]/g, (c) => `&#${c.charCodeAt(0)};`);
}
