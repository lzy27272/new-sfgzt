export function IcpFooter({ recordNumber, placement = 'page' }: {
  recordNumber: string
  placement?: 'page' | 'sidebar' | 'mobile'
}) {
  if (!recordNumber.trim()) return null

  return <footer className={`site-icp-footer${placement === 'page' ? '' : ` site-icp-footer--${placement}`}`} aria-label="网站备案信息">
    <span className="icp-record">{recordNumber}</span>
  </footer>
}
