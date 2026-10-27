# libVLC's native code calls back into its Java classes by name.
-keep class org.videolan.** { *; }
# Reads prefix-table.txt from its own package, so the package must keep its name.
-keepnames class dev.betteriptv.core.PrefixTable
