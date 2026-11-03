import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { App } from './App';

it('renders the Finova shell', () => {
  render(<App />);

  expect(screen.getByText('Finova')).toBeInTheDocument();
});
