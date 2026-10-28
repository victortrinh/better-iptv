/** What the subtitle services are asked about: a Movie, or an Episode of a Show (season and episode null for a Movie). */
export interface Searched {
  tmdbId: number;
  /** The Title's IMDb ID (`tt0068646`), when the cloud has TMDB's metadata for it: some subtitles are filed only under it. */
  imdbId: string | null;
  season: number | null;
  episode: number | null;
}

/** One subtitle file a service found. [ref] is how that service downloads it again; null [downloads] when it doesn't count them. */
export interface Found {
  ref: string;
  language: string;
  release: string;
  hearingImpaired: boolean;
  downloads: number | null;
}

/** A downloaded file as the service gave it: its name says its format. */
export interface ServiceFile {
  name: string;
  bytes: Uint8Array;
}

/** The service has no such file. */
export const NOT_FOUND = null;

/** The service couldn't answer right now (rate limit, outage, quota used up); worth asking again later. */
export class ServiceUnavailable extends Error {}

/** The subtitle formats the app plays, by file extension. */
export const SUBTITLE_FORMATS = ["srt", "vtt", "ass", "ssa"];
