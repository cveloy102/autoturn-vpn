# AutoTurn Android website

Public website: https://cveloy102.github.io/autoturn-vpn/

The selected design follows the HL100 palette and typography: dark background, red accents and monospace text. Application screenshots come from `docs/images`.

The `pages.yml` workflow assembles only the landing page and its four screenshots, then publishes it to GitHub Pages on changes to `main`. It does not include the separate personal website in `site/`.

To preview locally, copy `home.png`, `profiles.png`, `app-routing.png` and `settings.png` from `docs/images` into `landing/images`, then serve `landing` using any static HTTP server. No dependencies or build step are required.
