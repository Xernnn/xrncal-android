import React from 'react'
import { useDisplayPreferences } from '../../context/DisplayPreferencesContext'

export interface FormRowProps {
  /** Muted label shown in the left column (~115 px). */
  label: string
  /** Whether to show a 1 px hairline at the bottom of the row. */
  divider?: boolean
  /** Whether to align label to top for multiline content (notes, attendees). */
  alignTop?: boolean
  /** Additional className for the row wrapper. */
  className?: string
  /** Keep label and control on one row in the phone layout too - for a
   *  toggle, which is narrower than its label. */
  inline?: boolean
  children: React.ReactNode
}

/**
 * Notion-style property row with generous, uniform spacing.
 *
 * Layout:
 *   [label ~115px muted 12px/400]  [children grow]
 */
export const FormRow: React.FC<FormRowProps> = ({
  label,
  divider = false,
  alignTop = false,
  className = '',
  inline = false,
  children
}) => {
  const { compact } = useDisplayPreferences()

  // Phone layout: label above, field full width beneath it. A 115px label
  // column leaves a 390px screen too little room for a date and a time.
  if (compact && inline) {
    return (
      <div className={`flex min-h-[52px] items-center justify-between gap-3 px-1 py-1 ${divider ? 'border-b border-hairline' : ''} ${className}`}>
        <span className="text-[16px] font-normal text-primary select-none">{label}</span>
        <div className="shrink-0">{children}</div>
      </div>
    )
  }
  if (compact) {
    return (
      <div className={`flex flex-col gap-1 px-1 py-2 ${divider ? 'border-b border-hairline' : ''} ${className}`}>
        <span className="text-[13px] font-normal text-muted select-none">{label}</span>
        <div className="min-w-0">{children}</div>
      </div>
    )
  }

  return (
    <div
      className={`group flex ${
        alignTop ? 'items-start py-1.5' : 'items-center min-h-[38px] py-0.5'
      } px-1 transition-colors duration-100 ${
        divider ? 'border-b border-hairline' : ''
      } ${className}`}
    >
      {/* Label column */}
      <span
        className={`w-[115px] shrink-0 pr-3 text-[12px] font-normal text-muted select-none ${
          alignTop ? 'pt-1 leading-normal' : 'leading-none'
        }`}
      >
        {label}
      </span>

      {/* Value column */}
      <div className="flex-1 min-w-0">{children}</div>
    </div>
  )
}

export default FormRow
