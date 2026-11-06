import { describe, expect, it } from 'vitest';
import { humanise, isPast, money, sign, signedMoney, toIsoInstant, toLocalInput } from './format';

/**
 * The formatting rules worth pinning down, because money is involved.
 *
 * Amounts arrive from the backend as decimal strings and must survive the round trip to the screen without losing
 * paise or gaining a rounding error.
 */
describe('money', () => {
  it('shows a decimal string from the backend to the paisa', () => {
    expect(money('1050.00')).toBe('₹1,050.00');
    expect(money('133.33')).toBe('₹133.33');
    expect(money('0.01')).toBe('₹0.01');
  });

  it('groups in the Indian style', () => {
    expect(money('1234567.00')).toBe('₹12,34,567.00');
  });

  it('shows a dash rather than zero for nothing at all', () => {
    expect(money(null)).toBe('—');
    expect(money(undefined)).toBe('—');
    expect(money('')).toBe('—');
  });

  it('shows a real zero as zero', () => {
    expect(money('0.00')).toBe('₹0.00');
  });

  it('passes anything unparseable through rather than printing NaN', () => {
    expect(money('not money')).toBe('not money');
  });
});

describe('signedMoney', () => {
  it('marks the direction, because a profit and a loss read the same otherwise', () => {
    expect(signedMoney('50.00')).toBe('+₹50.00');
    expect(signedMoney('-100.00')).toBe('−₹100.00');
    expect(signedMoney('0.00')).toBe('₹0.00');
  });
});

describe('sign', () => {
  it('classifies a profit, a loss and breaking even', () => {
    expect(sign('50.00')).toBe('up');
    expect(sign('-0.01')).toBe('down');
    expect(sign('0.00')).toBe('flat');
    expect(sign(null)).toBe('flat');
  });
});

describe('humanise', () => {
  it('turns a backend enum into something readable', () => {
    expect(humanise('PENDING_SETTLEMENT')).toBe('Pending settlement');
    expect(humanise('WON')).toBe('Won');
    expect(humanise(null)).toBe('—');
  });
});

describe('local datetime round trip', () => {
  it('survives going to an input value and back to an instant', () => {
    const original = '2026-04-20T14:30:00.000Z';
    const asInput = toLocalInput(original);
    expect(asInput).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
    // Minutes are the finest the input offers, so the instant comes back to the same minute.
    expect(new Date(toIsoInstant(asInput)).getTime()).toBe(new Date(original).getTime());
  });
});

describe('isPast', () => {
  it('knows whether a deadline has gone', () => {
    expect(isPast(new Date(Date.now() - 1000).toISOString())).toBe(true);
    expect(isPast(new Date(Date.now() + 60_000).toISOString())).toBe(false);
    expect(isPast(null)).toBe(false);
  });
});
