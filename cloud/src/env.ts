export interface Env {
  DB: D1Database;
  /** TMDB's API root, `https://api.themoviedb.org/3`. */
  TMDB_API: string;
  /** TMDB's API read access token, or its short API key. A secret: it lives only in the cloud, never in the APK. */
  TMDB_TOKEN: string;
  /** Where Netflix publishes its Top 10 files, `https://www.netflix.com/tudum/top10/data`. */
  NETFLIX_TOP10: string;
  /** ESPN's site API root, `https://site.api.espn.com/apis/site/v2/sports`, whose scoreboard needs no key. */
  ESPN_API: string;
  /** OpenSubtitles' API root, `https://api.opensubtitles.com/api/v1`. */
  OPENSUBTITLES_API: string;
  /** The User-Agent OpenSubtitles asks every request to name the app by. */
  OPENSUBTITLES_USER_AGENT: string;
  /** Secrets, like TMDB_TOKEN. Without them, OpenSubtitles isn't asked. */
  OPENSUBTITLES_API_KEY?: string;
  OPENSUBTITLES_USERNAME?: string;
  OPENSUBTITLES_PASSWORD?: string;
  /** Subdl's API root, `https://api.subdl.com/api/v1`. */
  SUBDL_API: string;
  /** Where Subdl's zip files download from, `https://dl.subdl.com`. */
  SUBDL_DOWNLOADS: string;
  /** A secret. Without it, Subdl isn't asked. */
  SUBDL_API_KEY?: string;
  /**
   * Where the sign-in page is served, the app's own domain (`https://zugig.app`): the TV's QR and
   * the magic links point there. Unset, the address the request came to.
   */
  SITE_URL?: string;
  /** Who sign-in emails are from, `Zugig <sign-in@zugig.app>`. Unset, `sign-in@` the SITE_URL's host. */
  EMAIL_FROM?: string;
  /** The transactional sender's API root (Resend), `https://api.resend.com`. */
  RESEND_API: string;
  /** A secret. Without it, no sign-in email goes out and the page says so. */
  RESEND_API_KEY?: string;
}
