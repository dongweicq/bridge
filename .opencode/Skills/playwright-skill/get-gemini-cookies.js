const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const TARGET_URL = 'https://gemini.google.com';

// Find Edge executable path on Windows
const possibleEdgePaths = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  path.join(process.env.LOCALAPPDATA, 'Microsoft\\Edge\\Application\\msedge.exe')
];

let edgePath = null;
for (const p of possibleEdgePaths) {
  if (fs.existsSync(p)) {
    edgePath = p;
    break;
  }
}

(async () => {
  console.log('🚀 Starting browser to get Gemini cookies...');
  if (edgePath) {
    console.log(`🌐 Using installed Edge: ${edgePath}`);
  }
  console.log('📋 Please follow these steps:');
  console.log('   1. Browser will open and navigate to Gemini');
  console.log('   2. Please login with your Google account');
  console.log('   3. Once you see the Gemini chat interface, press ENTER in this terminal');
  console.log('   4. The script will then extract the cookies\n');

  const browser = await chromium.launch({
    channel: 'msedge',
    headless: false,
    slowMo: 100
  });

  const context = await browser.newContext({
    viewport: { width: 1280, height: 800 }
  });

  const page = await context.newPage();
  await page.goto(TARGET_URL, { waitUntil: 'networkidle' });

  console.log('✅ Browser opened. Please complete the login process.');
  console.log('⏳ Waiting for you to login... (Press ENTER when done)');

  // Wait for user to press ENTER
  await new Promise(resolve => {
    process.stdin.once('data', resolve);
  });

  // Get all cookies
  const cookies = await context.cookies();
  console.log(`\n🍪 Retrieved ${cookies.length} cookies`);

  // Filter for Google/Gemini related cookies
  const importantCookies = cookies.filter(cookie => {
    const name = cookie.name.toLowerCase();
    return name.includes('sid') ||
           name.includes('ssid') ||
           name.includes('hsid') ||
           name.includes('ssid') ||
           name.includes('auth') ||
           name.includes('secure') ||
           cookie.domain.includes('google');
  });

  console.log(`✅ Found ${importantCookies.length} important cookies`);

  // Format cookies for use
  const cookieString = importantCookies
    .map(c => `${c.name}=${c.value}`)
    .join('; ');

  // Save to file
  const cookieFile = 'D:\\ai\\aiproject\\ppt\\gemini-cookies.txt';
  fs.writeFileSync(cookieFile, cookieString);
  console.log(`\n💾 Cookies saved to: ${cookieFile}`);
  console.log('\n📋 Cookie string (ready to use):\n');
  console.log('─'.repeat(80));
  console.log(cookieString);
  console.log('─'.repeat(80));

  // Also save as JSON for reference
  const jsonFile = 'D:\\ai\\aiproject\\ppt\\gemini-cookies.json';
  fs.writeFileSync(jsonFile, JSON.stringify(importantCookies, null, 2));
  console.log(`\n📄 JSON format saved to: ${jsonFile}`);

  // Keep browser open for a moment so user can see success
  console.log('\n⏳ Keeping browser open for 5 seconds...');
  await page.waitForTimeout(5000);

  await browser.close();
  console.log('\n✅ Done!');
})();
