export interface Env {
  DB: D1Database;
  /** TMDB's API root, `https://api.themoviedb.org/3`. */
  TMDB_API: string;
  /** TMDB's API read access token, or its short API key. A secret: it lives only in the cloud, never in the APK. */
  TMDB_TOKEN: string;
  /** Where Netflix publishes its Top 10 files, `https://www.netflix.com/tudum/top10/data`. */
  NETFLIX_TOP10: string;
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
}
