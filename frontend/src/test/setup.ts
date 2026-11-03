import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterEach } from 'vitest';

// Coverage instrumentation slows the multi-step flows; the default 1 s wait made them flaky on CI-sized machines.
configure({ asyncUtilTimeout: 3000 });

afterEach(() => cleanup());
