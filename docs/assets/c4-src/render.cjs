const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch();
  for (const [file, w, h, out] of [['c4-context.html', 1600, 1080, '../finova-C4-context.png'], ['c4-containers.html', 2520, 1560, '../finova-C4.png']]) {
    const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
    await page.goto('file://' + __dirname + '/' + file);
    await page.screenshot({ path: __dirname + '/' + out, fullPage: false });
    await page.close();
  }
  await browser.close();
})();
