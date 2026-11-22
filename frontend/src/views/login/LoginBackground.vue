<template>
  <svg viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice" fill="none" aria-hidden="true" focusable="false">
    <defs>
      <radialGradient id="flow-login-glow-left" cx="0" cy="0" r="1" gradientTransform="translate(116 192) scale(690 620)" gradientUnits="userSpaceOnUse">
        <stop stop-color="currentColor" stop-opacity=".16" />
        <stop offset="1" stop-color="currentColor" stop-opacity="0" />
      </radialGradient>
      <radialGradient id="flow-login-glow-right" cx="0" cy="0" r="1" gradientTransform="translate(1294 766) scale(690 590)" gradientUnits="userSpaceOnUse">
        <stop stop-color="currentColor" stop-opacity=".2" />
        <stop offset="1" stop-color="currentColor" stop-opacity="0" />
      </radialGradient>
      <pattern id="flow-login-grid" width="28" height="28" patternUnits="userSpaceOnUse">
        <circle cx="2" cy="2" r="1" fill="currentColor" fill-opacity=".14" />
      </pattern>
      <linearGradient id="flow-login-trace-fade" x1="0" x2="1" y1="0" y2="0">
        <stop stop-color="white" stop-opacity=".35" />
        <stop offset=".14" stop-color="white" />
        <stop offset=".46" stop-color="white" stop-opacity=".22" />
        <stop offset=".55" stop-color="white" stop-opacity=".22" />
        <stop offset=".87" stop-color="white" />
        <stop offset="1" stop-color="white" stop-opacity=".45" />
      </linearGradient>
      <mask id="flow-login-trace-mask">
        <rect width="1440" height="900" fill="url(#flow-login-trace-fade)" />
      </mask>
    </defs>

    <rect width="1440" height="900" fill="url(#flow-login-glow-left)" />
    <rect width="1440" height="900" fill="url(#flow-login-glow-right)" />
    <rect width="1440" height="900" fill="url(#flow-login-grid)" />

    <g mask="url(#flow-login-trace-mask)" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
      <path d="M-28 176h218c96 0 88 114 184 114h42M-28 214h174c96 0 88 114 184 114h72M-28 252h132c96 0 88 114 184 114h90" stroke-opacity=".18" />
      <path d="M-28 124h268c96 0 88 114 184 114h84m-536 310h174c96 0 88-114 184-114h44" stroke-opacity=".25" stroke-dasharray="4 9" />
      <path d="M-28 298h90c96 0 88 114 184 114h100m-32-11 11 11-11 11" stroke-opacity=".4" stroke-width="1.5" />
      <path d="M-28 374H62c96 0 88-76 184-76h80" stroke-opacity=".26" />

      <path d="M1468 560h-218c-96 0-88 114-184 114h-42m444-76h-174c-96 0-88 114-184 114h-72m430-76h-132c-96 0-88 114-184 114h-90" stroke-opacity=".22" />
      <path d="M1468 508h-268c-96 0-88 114-184 114h-84m536 310h-174c-96 0-88-114-184-114h-44" stroke-opacity=".28" stroke-dasharray="4 9" />
      <path d="M1468 682h-90c-96 0-88 114-184 114h-100m34-11-11 11 11 11" stroke-opacity=".4" stroke-width="1.5" />
      <path d="M1468 758h-90c-96 0-88-76-184-76h-80" stroke-opacity=".3" />

      <g fill="currentColor" fill-opacity=".12" stroke-opacity=".4">
        <circle cx="190" cy="176" r="5" />
        <circle cx="424" cy="238" r="4" />
        <circle cx="246" cy="412" r="5" />
        <circle cx="1200" cy="508" r="4" />
        <circle cx="1066" cy="674" r="5" />
        <circle cx="1378" cy="758" r="5" />
      </g>
    </g>
  </svg>
</template>
