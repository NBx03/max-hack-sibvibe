import { Navigate, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useSession } from '../../app/SessionContext';
import { ROUTE_PATTERNS, joinPath } from '../../routes/paths';
import { CreateCompanyScreen } from './CreateCompanyScreen';
import { JoinByCodeScreen } from './JoinByCodeScreen';
import { PendingRequestScreen } from './PendingRequestScreen';
import { PersonalInviteScreen } from './PersonalInviteScreen';
import { WelcomeScreen } from './WelcomeScreen';

// Привязка экранов подключения к путям каркаса. Куда уходить после успеха,
// решают охранники в AppRoutes по свежему /me: экрану достаточно вызвать refresh.

export function WelcomeRoute() {
  const navigate = useNavigate();
  return (
    <WelcomeScreen
      onCreate={() => navigate(ROUTE_PATTERNS.createCompany)}
      onJoin={() => navigate(ROUTE_PATTERNS.join)}
    />
  );
}

export function CreateCompanyRoute() {
  const navigate = useNavigate();
  return <CreateCompanyScreen onBack={() => navigate(ROUTE_PATTERNS.welcome)} />;
}

export function JoinRoute() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  // Заявка по коду подаётся сама и при переходе сюда из приложения, не только по ссылке из проверенного startParam. Это
  // безопасно: внутри MAX адрес снаружи не задать, а вне MAX нет проверенных данных запуска — и нет входа. Заявка
  // доступа не даёт; решает администратор.
  const code = params.get('code') ?? undefined;
  // key: другой код в адресе пересоздаёт экран, и он проверяет код заново.
  return <JoinByCodeScreen key={code} initialCode={code} onBack={() => navigate(ROUTE_PATTERNS.welcome)} />;
}

export function PersonalInviteRoute() {
  const navigate = useNavigate();
  const { token } = useParams();
  if (!token) {
    return <Navigate to={ROUTE_PATTERNS.welcome} replace />;
  }
  return (
    <PersonalInviteScreen
      key={token}
      token={token}
      onJoinByCode={() => navigate(joinPath())}
      onDecline={() => navigate(ROUTE_PATTERNS.welcome)}
    />
  );
}

export function PendingRoute() {
  const { me } = useSession();
  // Охранник в AppRoutes пускает сюда только с заявкой; проверка здесь нужна для типов.
  if (!me?.pendingJoinRequest) {
    return <Navigate to={ROUTE_PATTERNS.welcome} replace />;
  }
  return <PendingRequestScreen request={me.pendingJoinRequest} />;
}
