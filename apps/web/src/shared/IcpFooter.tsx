export function IcpFooter({ recordNumber }: { recordNumber: string }) {
  if (!recordNumber.trim()) return null

  return <footer className="site-icp-footer" aria-label="网站备案信息">
    <a className="icp-record" href="https://beian.miit.gov.cn/" target="_blank" rel="noreferrer">{recordNumber}</a>
  </footer>
}
