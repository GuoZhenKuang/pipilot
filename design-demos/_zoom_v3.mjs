import { chromium } from 'playwright';
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });
await page.goto('file:///D:/20/_个人项目/pi-mobile/design-demos/v3-te-panel.html');
await page.waitForTimeout(600);
const phones = page.locator('.phone');
const n = await phones.count();
for (let i = 0; i < n; i++) {
  await phones.nth(i).screenshot({ path: `_zoom_v3_${i + 1}.png` });
}
console.log('captured', n);
await browser.close();
