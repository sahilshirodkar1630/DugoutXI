/**
 * The Dugout XI mark: three stumps with the bails on top. The stumps take the app's accent colour and the bails a
 * wooden amber (--bail), both of which change with Day and Dark, so the mark always sits right on the top bar. The
 * browser tab icon (public/favicon.svg) is the same drawing in fixed colours.
 */
export function BrandMark({ size = 24 }: { size?: number }) {
  return (
    <svg
      className="brand-mark"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      aria-hidden="true"
      focusable="false"
    >
      <rect x="5" y="7" width="3" height="15" rx="1.2" fill="var(--accent)" />
      <rect x="10.5" y="7" width="3" height="15" rx="1.2" fill="var(--accent)" />
      <rect x="16" y="7" width="3" height="15" rx="1.2" fill="var(--accent)" />
      <rect x="4" y="3.5" width="7.5" height="2" rx="1" fill="var(--bail)" />
      <rect x="12.5" y="3.5" width="7.5" height="2" rx="1" fill="var(--bail)" />
    </svg>
  );
}
