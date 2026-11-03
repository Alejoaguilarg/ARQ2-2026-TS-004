export function Backdrop() {
  return (
    <div className="backdrop" aria-hidden="true">
      <div className="blob violet" />
      <div className="blob teal" />
      <div className="blob coral" />
    </div>
  );
}

export function Brand() {
  return (
    <span className="brand">
      <span className="brand-mark" aria-hidden="true">
        <svg width="18" height="18" viewBox="0 0 32 32">
          <path d="M10 23V9h13M10 16h9" fill="none" stroke="#fff" strokeWidth="3" strokeLinecap="round" />
        </svg>
      </span>
      Finova
    </span>
  );
}
