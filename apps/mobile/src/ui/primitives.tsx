import type { JSX, ReactNode } from "react";
import {
  ActivityIndicator,
  Image,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
  type ImageSourcePropType,
  type TextInputProps,
  type ViewStyle,
} from "react-native";
import { tokens } from "../theme.js";
import markPng from "../../assets/tokito-mark.png";

const markSource: ImageSourcePropType = markPng;

export function BrandTile({ size = 36 }: { size?: number }): JSX.Element {
  return (
    <View
      style={{
        width: size,
        height: size,
        borderRadius: tokens.radiusSm,
        backgroundColor: tokens.brandTile,
        alignItems: "center",
        justifyContent: "center",
        overflow: "hidden",
      }}
    >
      <Image
        source={markSource}
        style={{ width: size * 0.7, height: size * 0.7 }}
        resizeMode="contain"
      />
    </View>
  );
}

export function PrimaryButton({
  label,
  onPress,
  busy,
  disabled,
  danger,
  style,
}: {
  label: string;
  onPress: () => void;
  busy?: boolean;
  disabled?: boolean;
  danger?: boolean;
  style?: ViewStyle | undefined;
}): JSX.Element {
  return (
    <Pressable
      accessibilityRole="button"
      disabled={disabled === true || busy === true}
      onPress={onPress}
      style={({ pressed }) => [
        styles.btn,
        danger === true ? styles.btnDanger : styles.btnAccent,
        (disabled === true || busy === true) && styles.btnDisabled,
        pressed && styles.btnPressed,
        style,
      ]}
    >
      {busy === true ? (
        <ActivityIndicator color={danger === true ? tokens.text : tokens.accentInk} />
      ) : (
        <Text
          style={[
            styles.btnText,
            { color: danger === true ? tokens.text : tokens.accentInk },
          ]}
        >
          {label}
        </Text>
      )}
    </Pressable>
  );
}

export function GhostButton({
  label,
  onPress,
  disabled,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
}): JSX.Element {
  return (
    <Pressable
      accessibilityRole="button"
      disabled={disabled === true}
      onPress={onPress}
      style={({ pressed }) => [styles.ghost, pressed && { opacity: 0.7 }]}
    >
      <Text style={styles.link}>{label}</Text>
    </Pressable>
  );
}

export function Field({
  label,
  ...props
}: { label: string } & TextInputProps): JSX.Element {
  return (
    <View style={styles.field}>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        placeholderTextColor={tokens.text3}
        style={styles.input}
        {...props}
      />
    </View>
  );
}

export function Card({
  children,
  onPress,
  style,
}: {
  children: ReactNode;
  onPress?: () => void;
  style?: ViewStyle | undefined;
}): JSX.Element {
  if (onPress !== undefined) {
    return (
      <Pressable
        onPress={onPress}
        style={({ pressed }) => [
          styles.card,
          pressed && { backgroundColor: tokens.cardHover },
          style,
        ]}
      >
        {children}
      </Pressable>
    );
  }
  return <View style={[styles.card, style]}>{children}</View>;
}

export function SectionLabel({ children }: { children: string }): JSX.Element {
  return <Text style={styles.section}>{children}</Text>;
}

export function Muted({ children }: { children: ReactNode }): JSX.Element {
  return <Text style={styles.muted}>{children}</Text>;
}

export function ErrorText({ children }: { children: string }): JSX.Element {
  return <Text style={styles.error}>{children}</Text>;
}

const styles = StyleSheet.create({
  btn: {
    borderRadius: tokens.radiusSm,
    paddingVertical: 12,
    paddingHorizontal: tokens.space4,
    alignItems: "center",
    justifyContent: "center",
    minHeight: 44,
  },
  btnAccent: { backgroundColor: tokens.accent },
  btnDanger: { backgroundColor: tokens.danger },
  btnDisabled: { opacity: 0.55 },
  btnPressed: { opacity: 0.88 },
  btnText: { fontWeight: "600", fontSize: 15, letterSpacing: 0.2 },
  ghost: { paddingVertical: tokens.space2 },
  link: { color: tokens.accent, fontSize: 14, fontWeight: "500" },
  field: { gap: tokens.space2 },
  label: {
    color: tokens.text2,
    fontSize: 12,
    fontWeight: "500",
    letterSpacing: 0.3,
    textTransform: "uppercase",
  },
  input: {
    borderWidth: 1,
    borderColor: tokens.border,
    backgroundColor: tokens.card,
    color: tokens.text,
    borderRadius: tokens.radiusSm,
    paddingHorizontal: tokens.space4,
    paddingVertical: 12,
    fontSize: 16,
  },
  card: {
    backgroundColor: tokens.card,
    borderColor: tokens.border,
    borderWidth: 1,
    borderRadius: tokens.radiusMd,
    padding: tokens.space4,
  },
  section: {
    color: tokens.text,
    fontSize: 15,
    fontWeight: "600",
    marginTop: tokens.space3,
    letterSpacing: 0.2,
  },
  muted: { color: tokens.text2, fontSize: 13, lineHeight: 18 },
  error: { color: tokens.danger, fontSize: 14, lineHeight: 20 },
});
