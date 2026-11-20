# Third-Party Licenses

SplitKey bundles the following third-party components into
`libsplitkey_rime.so`. All of them are permissive licenses; there is no
copyleft obligation that reaches SplitKey's own source code.

Full license texts are in this directory.

| Component | Version | License | File |
|---|---|---|---|
| [librime](https://github.com/rime/librime) | 1.17.0 | BSD-3-Clause | `librime-BSD-3-Clause.txt` |
| [Boost](https://www.boost.org/) | 1.86.0 | Boost Software License 1.0 | `boost-BSL-1.0.txt` |
| [LevelDB](https://github.com/google/leveldb) | — | BSD-3-Clause | `leveldb-BSD-3-Clause.txt` |
| [yaml-cpp](https://github.com/jbeder/yaml-cpp) | — | MIT | `yaml-cpp-MIT.txt` |
| [marisa-trie](https://github.com/s-yata/marisa-trie) | 0.3.1 | BSD-2-Clause **or** LGPL-2.1-or-later | `marisa-trie-BSD-2-Clause-OR-LGPL-2.1.md` |
| [OpenCC](https://github.com/BYVoid/OpenCC) | — | Apache-2.0 | `opencc-Apache-2.0.txt` |

## Notes

**marisa-trie is dual-licensed.** Its license reads "BSD-2-Clause OR
LGPL-2.1-or-later", and the choice belongs to the downstream user. SplitKey
takes it under **BSD-2-Clause**. Do not assume the LGPL branch.

**OpenCC is Apache-2.0**, which requires that the license text and any
NOTICE file be preserved when redistributing binaries. Both are kept here.

**librime, LevelDB and marisa-trie are BSD-licensed**, which requires that
the copyright notice, the list of conditions, and the disclaimer be
reproduced in the documentation accompanying a binary distribution. That is
what this directory is for: if you ship an APK, ship this directory with it
(or expose the same text from within the app).

## Rime input schemas / dictionaries

The engine is separate from its data. Dictionary schemas (`luna_pinyin`,
etc.) are authored by the Rime community and carry their own licenses,
which may differ from the engine's. Check each schema's license before
bundling it.
