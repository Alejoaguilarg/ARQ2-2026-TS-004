import { expect, test, type Page } from '@playwright/test';

// The customer and auditor journeys on the real stack: Gateway, Identity, Transfer saga, Wallet and Audit.

async function signIn(page: Page, email: string) {
  await page.goto('/ingresar');
  await page.getByLabel('Correo').fill(email);
  await page.getByLabel('Contraseña', { exact: true }).fill('Finova2026!');
  await page.getByRole('button', { name: 'Ingresar' }).click();
}

async function send(page: Page, to: string, amount: string) {
  await page.getByRole('button', { name: 'Enviar dinero' }).click();
  await page.getByLabel('Correo de quien recibe').fill(to);
  await page.getByLabel('Monto en pesos').fill(amount);
  await page.getByLabel('Descripción (opcional)').fill('Prueba e2e');
  await page.getByRole('button', { name: 'Continuar' }).click();
  await page.getByLabel('PIN de 4 dígitos').fill('4821');
  await page.getByRole('button', { name: 'Confirmar y enviar' }).click();
}

const pesos = (text: string) => Number(text.replace(/[^\d,]/g, '').replace(',', '.'));

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

  await expect(page.getByText('Listo. Beto ya recibió el dinero.')).toBeVisible({ timeout: 60_000 });
  await expect.poll(async () => pesos(await balance.innerText())).toBe(before - 1000);
  await expect(page.getByRole('button', { name: /Beto.*Completado/ })).toBeVisible();
});

test('a send larger than the balance is rejected without charging anything', async ({ page }) => {
  await signIn(page, 'ana@example.com');
  const balance = page.locator('.balance');
  await expect(balance).toContainText('$');
  const before = pesos(await balance.innerText());

  await send(page, 'beto@example.com', '999999999');

  await expect(page.getByText('No tienes saldo suficiente para este envío. No se descontó nada.')).toBeVisible({ timeout: 60_000 });
  await page.getByRole('button', { name: 'Actualizar saldo' }).click();
  await expect.poll(async () => pesos(await balance.innerText())).toBe(before);
});

test('the auditor follows a whole transfer and finds the record intact', async ({ page }) => {
  await signIn(page, 'auditor@example.com');
  await expect(page).toHaveURL(/\/actividad$/);

  await page.getByText('Ana no tenía saldo suficiente').first().click();
  await page.getByRole('button', { name: 'Ver todo este envío' }).click();
  await expect(page.getByText('3 movimientos')).toBeVisible();
  await expect(page.locator('.audit-row')).toHaveText([/Ana pidió enviar dinero/, /Ana no tenía saldo suficiente/, /Envío rechazado/]);

  await page.getByRole('button', { name: 'Revisar ahora' }).click();
  await expect(page.getByRole('status')).toContainText('Todo en orden.');
});

test('a signed-out visitor is sent to sign in and a customer cannot open Actividad', async ({ page }) => {
  await page.goto('/actividad');
  await expect(page).toHaveURL(/\/ingresar$/);
  await signIn(page, 'beto@example.com');
  await expect(page).toHaveURL(/\/billetera$/);
  await page.goto('/actividad');
  await expect(page).toHaveURL(/\/billetera$/);
  await expect(page.getByRole('navigation', { name: 'Secciones' })).toHaveCount(0);
});
