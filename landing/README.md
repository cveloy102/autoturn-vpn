# AutoTurn Android website

Public website: https://cveloy102.github.io/autoturn-vpn/

The design follows the app's Terminal 2.0 look: dark background, red accents, Inter Display Black headings, JetBrains Mono text and rounded corners. Screenshots come from `docs/images`, fonts from `app/src/main/res/font`.

The `pages.yml` workflow assembles the landing page, five screenshots and three fonts, then publishes it to GitHub Pages on changes to `main`. It does not include the separate personal website in `site/`.

To preview locally, copy `home-online.png`, `profiles.png`, `app-routing.png`, `settings.png` and `home-light.png` from `docs/images` into `landing/images`, the three fonts into `landing/fonts`, then serve `landing` with any static HTTP server. No dependencies or build step are required.
