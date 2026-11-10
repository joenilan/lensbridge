# Contributing to Lensbridge

Thanks for helping. Bug reports, ideas and pull requests are all welcome.

- **Bugs and ideas:** open an issue using one of the templates. Include your versions and `logs/latest.log`. Never share `config/lensbridge.json`; it contains your secret key.
- **Code:** read [AGENTS.md](AGENTS.md) first for the layout, threading rules and how tools are added. Build with `./gradlew build` (Java 21). Keep changes focused and update the docs that describe what you changed (`README.md`, `docs/USER_GUIDE.md`, `docs/AGENT_GUIDE.md`, `docs/TOOLS.md`).
- **Multiplayer manners:** Lensbridge respects server rules by default (see "Playing on servers" in the user guide). Changes that would let players get around a server's rules won't be accepted.
- **Security:** the bridge only listens on 127.0.0.1 and every request needs the token. If you find a way around either, please report it privately (GitHub's "Report a vulnerability" on this repo) rather than in a public issue.

By contributing you agree your work is released under the [MIT License](LICENSE).
