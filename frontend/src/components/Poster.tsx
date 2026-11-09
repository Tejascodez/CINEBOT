/** Poster with a generated fallback (dev seed movies have no TMDB images). */
export function Poster({ url, title, className = '' }: { url: string | null; title: string; className?: string }) {
  if (url) return <img src={url} alt={title} loading="lazy" className={`aspect-[2/3] w-full rounded-lg object-cover ${className}`} />
  const hue = [...title].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7)
  return (
    <div
      aria-label={title}
      className={`flex aspect-[2/3] w-full items-end rounded-lg p-3 ${className}`}
      style={{ background: `linear-gradient(160deg, hsl(${hue} 60% 35%), hsl(${(hue + 60) % 360} 50% 12%))` }}
    >
      <span className="text-lg font-bold leading-tight text-white/90 drop-shadow">{title}</span>
    </div>
  )
}
