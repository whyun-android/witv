# WiTV 宣传网站

纯静态页面，没有构建步骤：`index.html` + `style.css` + `app.js` + `assets/`。

- 本地预览：直接用浏览器打开 `index.html`，或在本目录运行 `python -m http.server`。
- 首屏的电视是用 HTML 搭的演示，可以用方向键 / 页面上的按钮换台、切换两套主题。
  主题配色与 `app/src/main/res/values/themes.xml` 保持一致，改应用主题时记得同步这里的 `.tv[data-theme=…]`。
- 演示里的频道和节目都是虚构的，不要换成真实电视台的名称或画面。
- 文案在 `app.js` 的 `I18N` 里（中文 / English），`index.html` 里的中文是默认值和无 JS 时的兜底。

部署到 GitHub Pages 时，把本目录作为站点根目录发布即可（例如用 `actions/upload-pages-artifact` 指定 `path: website`）。
