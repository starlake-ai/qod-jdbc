// Plays the user's browser: opens the URL the driver hands it, signs in at Keycloak, and
// follows the manager's redirect to the driver's loopback listener.
import { chromium } from 'playwright';
const [url, user, password] = process.argv.slice(2);
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage();
const hops = [];
page.on('framenavigated', f => {
  if (f !== page.mainFrame()) return;
  const u = new URL(f.url());
  const cid = u.searchParams.get('client_id');
  hops.push(u.origin + u.pathname + (cid ? ' [client_id=' + cid + ']' : ''));
});
try {
  await page.goto(url);
  if (await page.locator('#username').count()) {
    await page.fill('#username', user);
    await page.fill('#password', password);
    await page.click('#kc-login');
  }
  await page.waitForURL(u => u.hostname === '127.0.0.1', { timeout: 20000 });
  await page.waitForLoadState();
  console.log('hops: ' + [...new Set(hops)].join(' -> '));
  console.log('page: ' + (await page.textContent('body')).trim());
} catch (e) {
  console.log('drive failed at ' + page.url().split('?')[0] + ': ' + e.message.split('\n')[0]);
  process.exitCode = 1;
} finally {
  await browser.close();
}
