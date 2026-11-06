import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithRouter } from '../test/render';
import { AboutPage } from './AboutPage';

/** The About page: open to everyone, with the guide to download and the builder's contact details. */
describe('AboutPage', () => {
  it('offers both editions of the user guide as downloads', () => {
    renderWithRouter(<AboutPage />);

    expect(screen.getByRole('link', { name: 'Download guide (Day)' })).toHaveAttribute(
      'href',
      '/guide/Dugout-XI-User-Guide.pdf',
    );
    expect(screen.getByRole('link', { name: 'Download guide (Dark)' })).toHaveAttribute(
      'href',
      '/guide/Dugout-XI-User-Guide-Dark.pdf',
    );
  });

  it('says who built it and how to reach him', () => {
    renderWithRouter(<AboutPage />);

    expect(screen.getByRole('heading', { name: 'Built by Sahil Shirodkar' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Email sahilshirodkar1630@gmail.com/ })).toHaveAttribute(
      'href',
      expect.stringContaining('mailto:sahilshirodkar1630@gmail.com'),
    );
    expect(screen.getByRole('link', { name: 'Call 9867141653' })).toHaveAttribute('href', 'tel:+919867141653');
    expect(screen.queryByRole('link', { name: 'WhatsApp' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'LinkedIn' })).toHaveAttribute(
      'href',
      'https://www.linkedin.com/in/sahilshirodkar/',
    );
  });

  it('puts the builder card straight after the introduction', () => {
    renderWithRouter(<AboutPage />);
    const order = [...document.querySelectorAll('h1, h2')].map((h) => h.textContent);
    expect(order.slice(0, 3)).toEqual(['Dugout XI', 'Built by Sahil Shirodkar', 'User guide']);
  });

  it('has a way back to sign in when opened signed out', () => {
    renderWithRouter(<AboutPage standalone />);
    expect(screen.getByRole('link', { name: '← Sign in' })).toHaveAttribute('href', '/sign-in');
  });
});
