import { chromium } from 'playwright';
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const ctx = await browser.newContext();
const page = await ctx.newPage();
try {
  await page.goto('http://localhost:20900/api/auth/oidc/start?tenant=acme');
  await page.fill('#username', 'acme-admin');
  await page.fill('#password', 'acme-admin-kc');
  await page.click('#kc-login');
  await page.waitForURL(u => u.hostname === 'localhost' && u.port === '20900' && u.pathname.startsWith('/ui'), { timeout: 20000 });
  const cookies = (await ctx.cookies('http://localhost:20900/api')).map(c => c.name);
  console.log('landed: ' + page.url());
  console.log('cookies: ' + cookies.join(','));
  const who = await page.request.get('http://localhost:20900/api/auth/whoami');
  console.log('whoami: ' + who.status() + ' ' + (await who.text()).slice(0, 160));
} catch (e) { console.log('console login failed at ' + page.url() + ': ' + e.message.split('\n')[0]); process.exitCode = 1; }
finally { await browser.close(); }
