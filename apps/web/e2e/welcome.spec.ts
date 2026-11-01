import { expect, test } from '@playwright/test';

test.describe('ParcelFlow sign-in experience', () => {
  test('explains the synthetic product and offers secure sign-in', async ({ page }) => {
    await page.goto('/');

    await expect(page).toHaveTitle(/ParcelFlow/);
    await expect(page.getByRole('heading', { name: 'Move money with clarity.' })).toBeVisible();
    await expect(page.getByText('Demo data only · No real funds are moved')).toBeVisible();
    await expect(page.getByRole('button', { name: /sign in securely/i })).toBeVisible();
    await expect(page.getByText('Protected with OpenID Connect · PKCE')).toBeVisible();
  });

  test('keeps the sign-in call to action usable on a narrow viewport', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await page.goto('/');

    const signIn = page.getByRole('button', { name: /sign in securely/i });
    await expect(signIn).toBeVisible();
    await expect(signIn).toBeEnabled();
    await expect(page.getByRole('heading', { name: 'Move money with clarity.' })).toBeVisible();
  });
});
