import React, { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useDisplayPreferences } from '../../context/DisplayPreferencesContext'

export interface ConfirmOptions {
  /** Label of the confirming button; defaults to OK. */
  confirmLabel?: string
  /** Paint the confirming button in the today/danger colour. */
  destructive?: boolean
}

interface PendingConfirm extends ConfirmOptions {
  message: string
  resolve: (confirmed: boolean) => void
}

/** Set while a ConfirmHost in the phone layout is mounted. */
let presentInApp: ((request: PendingConfirm) => void) | null = null

/**
 * Ask a yes/no question.
 *
 * On desktop this is the native confirm(), which Electron draws as a proper
 * system dialog. In the phone layout it is not: the WebView's confirm() is a
 * stock white Android AlertDialog with teal buttons, ignores the theme, blocks
 * the page's JavaScript while it is up, and stacks over whatever keyboard was
 * open. There ConfirmHost draws the question as one of xrncal's own sheets.
 */
export function askConfirm(message: string, options: ConfirmOptions = {}): Promise<boolean> {
  if (!presentInApp) return Promise.resolve(window.confirm(message))
  const present = presentInApp
  return new Promise((resolve) => present({ message, ...options, resolve }))
}

/** Mounted once by App; only takes over confirm() in the phone layout. */
export const ConfirmHost: React.FC = () => {
  const { t } = useTranslation()
  const { compact } = useDisplayPreferences()
  const [pending, setPending] = useState<PendingConfirm | null>(null)

  useEffect(() => {
    if (!compact) return
    presentInApp = (request) => {
      // The question is the next thing to answer; a keyboard left up by the
      // editor underneath would cover half of it.
      if (document.activeElement instanceof HTMLElement) document.activeElement.blur()
      setPending(request)
    }
    return () => {
      presentInApp = null
    }
  }, [compact])

  const settle = useCallback(
    (confirmed: boolean) => {
      pending?.resolve(confirmed)
      setPending(null)
    },
    [pending]
  )

  useEffect(() => {
    if (!pending) return
    const onKeyDown = (e: KeyboardEvent): void => {
      if (e.key !== 'Escape') return
      // Captured and stopped here: the editor under this sheet closes on
      // Escape too, and one Back must dismiss one layer.
      e.preventDefault()
      e.stopImmediatePropagation()
      settle(false)
    }
    window.addEventListener('keydown', onKeyDown, true)
    return () => window.removeEventListener('keydown', onKeyDown, true)
  }, [pending, settle])

  if (!pending) return null

  return (
    <div
      className="gc-overlay select-none"
      style={{ zIndex: 70 }}
      onClick={(e) => {
        if (e.target === e.currentTarget) settle(false)
      }}
    >
      <div className="gc-dialog w-full max-w-sm p-5" role="alertdialog" aria-modal="true">
        <p className="mb-5 text-[15px] leading-snug text-primary">{pending.message}</p>
        <div className="flex justify-end gap-2">
          <button type="button" onClick={() => settle(false)} className="gc-btn h-11 px-4 text-[14px]">
            {t('common.cancel')}
          </button>
          <button
            type="button"
            onClick={() => settle(true)}
            className={`h-11 px-4 text-[14px] font-semibold text-white transition-opacity active:opacity-80 ${
              pending.destructive ? 'bg-today' : 'bg-accent'
            }`}
            style={{ borderRadius: 'var(--radius-control)' }}
          >
            {pending.confirmLabel ?? t('common.ok')}
          </button>
        </div>
      </div>
    </div>
  )
}
