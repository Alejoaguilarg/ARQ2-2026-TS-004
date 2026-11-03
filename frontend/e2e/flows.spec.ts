import { expect, test, type Page } from '@playwright/test';

// The customer and auditor journeys on the real stack: Gateway, Identity, Transfer saga, Wallet and Audit.

async function signIn(page: Page, email: string) {
  await page.goto('/sign-in');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill('Finova2026!');
  await page.getByRole('button', { name: 'Sign in' }).click();
}

async function send(page: Page, to: string, amount: string) {
  await page.getByRole('button', { name: 'Send money' }).click();
  await page.getByLabel('Recipient email').fill(to);
  await page.getByLabel('Amount in pesos').fill(amount);
  await page.getByLabel('Description (optional)').fill('Prueba e2e');
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.getByLabel('4-digit PIN').fill('4821');
  await page.getByRole('button', { name: 'Confirm and send' }).click();
}

const pesos = (text: string) => Number(text.replace(/[^\d.]/g, ''));

let consoleErrors: string[] = [];

test.beforeEach(({ page }) => {
  consoleErrors = [];
  page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()));
});

// A CSP violation or a crash shows up here first.
test.afterEach(() => expect(consoleErrors, 'browser console errors').toEqual([]));

test('Ana sends money to Beto and her balance goes down by that amount', async ({ page }) => {
  await signIn(page, 'ana@example.com');
  const balance = page.locator('.balance');
  await expect(balance).toContainText('$');
  const before = pesos(await balance.innerText());

  await send(page, 'beto@example.com', '1000');

  await expect(page.getByText('Done. Beto received the money.')).toBeVisible({ timeout: 60_000 });
  await expect.poll(async () => pesos(await balance.innerText())).toBe(before - 1000);
  await expect(page.getByRole('button', { name: /Beto.*Completed/ })).toBeVisible();
});

test('a send larger than the balance is rejected without charging anything', async ({ page }) => {
  await signIn(page, 'ana@example.com');
  const balance = page.locator('.balance');
  await expect(balance).toContainText('$');
  const before = pesos(await balance.innerText());

  await send(page, 'beto@example.com', '999999999');

  await expect(page.getByText('You do not have enough balance for this transfer. Nothing was taken.')).toBeVisible({ timeout: 60_000 });
  await page.getByRole('button', { name: 'Refresh balance' }).click();
  await expect.poll(async () => pesos(await balance.innerText())).toBe(before);
});

test('the auditor follows a whole transfer and finds the record intact', async ({ page }) => {
  await signIn(page, 'auditor@example.com');
  await expect(page).toHaveURL(/\/activity$/);

  await page.getByText('Ana did not have enough balance').first().click();
  await page.getByRole('button', { name: 'See this whole transfer' }).click();
  await expect(page.getByText('3 records')).toBeVisible();
  await expect(page.locator('.audit-row')).toHaveText([/Ana asked to send money/, /Ana did not have enough balance/, /Transfer rejected/]);

  await page.getByRole('button', { name: 'Check now' }).click();
  await expect(page.getByRole('status')).toContainText('All good.');
});

test('a signed-out visitor is sent to sign in and a customer cannot open Activity', async ({ page }) => {
  await page.goto('/activity');
  await expect(page).toHaveURL(/\/sign-in$/);
  await signIn(page, 'beto@example.com');
  await expect(page).toHaveURL(/\/wallet$/);
  await page.goto('/activity');
  await expect(page).toHaveURL(/\/wallet$/);
  await expect(page.getByRole('navigation', { name: 'Sections' })).toHaveCount(0);
});
