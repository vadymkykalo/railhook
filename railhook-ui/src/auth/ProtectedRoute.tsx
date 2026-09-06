import { lazy, Suspense } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from './auth.store';

const AccessDeniedPage = lazy(() => import('../pages/AccessDeniedPage'));

export type Role = 'OWNER' | 'DEVELOPER' | 'VIEWER';

const ROLE_HIERARCHY: Record<Role, number> = {
  VIEWER: 0,
  DEVELOPER: 1,
  OWNER: 2,
};

interface ProtectedRouteProps {
  children: React.ReactNode;
  requiredRole?: Role;
}

export default function ProtectedRoute({ children, requiredRole }: ProtectedRouteProps) {
  const { isAuthenticated, user } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  if (requiredRole) {
    const userRole = (user?.role || 'VIEWER') as Role;
    if (ROLE_HIERARCHY[userRole] < ROLE_HIERARCHY[requiredRole]) {
      return <Suspense fallback={null}><AccessDeniedPage /></Suspense>;
    }
  }

  return <>{children}</>;
}

export function hasMinRole(current: Role, required: Role): boolean {
  return ROLE_HIERARCHY[current] >= ROLE_HIERARCHY[required];
}
