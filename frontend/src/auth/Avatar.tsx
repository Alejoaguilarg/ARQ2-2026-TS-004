// The sign-in robot: follows the email as it is typed, covers its eyes for the password (peeks with one if the
// password is shown), celebrates a good sign-in and shakes its head at a wrong one.

export type Mood = 'idle' | 'ok' | 'fail';
export type Focus = 'none' | 'email' | 'password';

interface Props {
  focus: Focus;
  emailLength: number;
  showPassword: boolean;
  mood: Mood;
}

const MOUTHS = {
  idle: 'M104 148 Q120 158 136 148',
  typing: 'M108 150 Q120 154 132 150',
  covered: 'M113 150 Q120 156 127 150',
  ok: 'M98 142 Q120 170 142 142',
  fail: 'M104 156 Q120 142 136 156',
};

export function Avatar({ focus, emailLength, showPassword, mood }: Props) {
  const covering = focus === 'password' && mood === 'idle';
  const peeking = covering && showPassword;
  const length = Math.min(emailLength, 30);
  let px = 0;
  let py = 0;
  let tilt = 0;
  if (focus === 'email' && mood === 'idle') {
    px = -8 + (length / 30) * 16;
    py = 6;
    tilt = -6 + (length / 30) * 12;
  }
  if (peeking) {
    px = -6;
    py = 7;
  }
  if (mood === 'fail') {
    py = 4;
  }
  const mouth = mood !== 'idle' ? MOUTHS[mood] : covering ? MOUTHS.covered : focus === 'email' ? MOUTHS.typing : MOUTHS.idle;
  const left = covering ? (peeking ? [16, -56] : [18, -100]) : [0, 0];
  const right = covering ? [-18, -100] : mood === 'ok' ? [16, -150] : [0, 0];
  const label = mood === 'ok' ? 'El asistente celebra'
    : mood === 'fail' ? 'El asistente niega con la cabeza'
      : covering && !peeking ? 'El asistente se tapa los ojos'
        : peeking ? 'El asistente espía con un ojo' : 'El asistente te mira';

  return (
    <svg className={`avatar avatar-${mood}`} width="168" height="168" viewBox="0 0 240 240" role="img" aria-label={label}>
      <defs>
        <linearGradient id="avatar-body" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#8B7BFF" />
          <stop offset="1" stopColor="#2DD4BF" />
        </linearGradient>
        <linearGradient id="avatar-hand" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#A99BFF" />
          <stop offset="1" stopColor="#5EEAD4" />
        </linearGradient>
      </defs>
      <g className="avatar-body">
        <g className="avatar-head" style={{ transform: `rotate(${tilt.toFixed(1)}deg)` }}>
          <line x1="120" y1="40" x2="120" y2="18" stroke="#B9ACFF" strokeWidth="5" strokeLinecap="round" />
          <circle className="avatar-antenna" cx="120" cy="14" r="9" />
          <circle cx="120" cy="128" r="90" fill="url(#avatar-body)" />
          <ellipse cx="120" cy="124" rx="66" ry="50" fill="#0B1022" />
          <g className="avatar-blush">
            <circle cx="76" cy="152" r="10" fill="#FB7185" />
            <circle cx="164" cy="152" r="10" fill="#FB7185" />
          </g>
          <g className="avatar-eyes">
            <g className="avatar-blink">
              <ellipse cx="96" cy="118" rx="15" ry="16" fill="#F3F5FB" />
              <ellipse cx="144" cy="118" rx="15" ry="16" fill="#F3F5FB" />
            </g>
            <g className="avatar-pupils" style={{ transform: `translate(${px.toFixed(1)}px, ${py}px)` }}>
              <circle cx="96" cy="118" r="7.5" fill="#0B1022" />
              <circle cx="144" cy="118" r="7.5" fill="#0B1022" />
              <circle cx="99" cy="115" r="2.5" fill="#fff" />
              <circle cx="147" cy="115" r="2.5" fill="#fff" />
            </g>
          </g>
          <g className="avatar-happy-eyes" fill="none" stroke="#5EEAD4" strokeWidth="6" strokeLinecap="round">
            <path d="M82 122 Q96 104 110 122" />
            <path d="M130 122 Q144 104 158 122" />
          </g>
          <g className="avatar-brows" stroke="#FCA5A5" strokeWidth="5" strokeLinecap="round">
            <path d="M80 98 L108 90" />
            <path d="M160 98 L132 90" />
          </g>
          <path className="avatar-mouth" d={mouth} fill="none" strokeWidth="5" strokeLinecap="round" />
        </g>
        <g className="avatar-hand" style={{ transform: `translate(${left[0]}px, ${left[1]}px)` }}>
          <ellipse cx="78" cy="222" rx="26" ry="22" fill="url(#avatar-hand)" stroke="rgba(255,255,255,0.55)" strokeWidth="2" />
        </g>
        <g className="avatar-hand" style={{ transform: `translate(${right[0]}px, ${right[1]}px)` }}>
          <ellipse cx="162" cy="222" rx="26" ry="22" fill="url(#avatar-hand)" stroke="rgba(255,255,255,0.55)" strokeWidth="2" />
        </g>
      </g>
    </svg>
  );
}
