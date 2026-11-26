import SideRail from "@/components/SideRail";
import SiteFooter from "@/components/SiteFooter";
import HoloPointer from "@/components/HoloPointer";
import SmoothScroll from "@/lib/smooth";
import "./main.css";
import "./space.css";
import "./flow.css";
import "./pro.css";

export default function MainLayout({ children }: { children: React.ReactNode }) {
  return (
    <>
      <SmoothScroll />
      <HoloPointer />
      <SideRail>
        {children}
        <SiteFooter />
      </SideRail>
    </>
  );
}
