---
status: accepted
date: 2026-10-28
---

# The Lower Version ranks by the playing file's quality, not by Source

After repeated buffering the player suggests a Lower Version. It ranked Versions by quality, and a plain `EN - …` Movie has no quality its Prefix can tell, so on a 1080p TV the suggestion never came. We now take the playing Version's quality from what the player found in the file, which costs no request to the Provider, and look through every Version in its language, Backups included: Versions grouped as Backups because neither quality was known before Play may no longer be equivalent.

We rejected suggesting a same-quality Version from another Source: it may be just as slow, and the Viewer would be told it's lower when it isn't. We also rejected staying silent on 1080p, since the fix is cheap. In the Provider's catalog, 91% of English Movies (16,980 of 18,568) have only one non-4K English Version, so a Lower Version can appear for about one Movie in eleven whatever the rule.

## Consequences

- An unread Version still counts as the lowest quality, so the suggestion may be a file as good as the playing one.
- It depends on the Player engine reporting the picture's size. Media3 does; with VLC the playing Version's quality stays what the Prefix, Header reads and earlier Playback said.
